package app.foscal.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE
import kotlin.math.abs

/**
 * One VEVENT in the app's own terms — the interchange shape between an `.ics` file and
 * [EventInput] / [Event].
 *
 * [end] follows the same convention the Calendar Provider uses, so no translation is needed at
 * either boundary: for all-day events it is *exclusive* (a one-day event ends the next midnight),
 * for timed events it is the actual end instant.
 */
data class IcsEvent(
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String? = null,
    val description: String? = null,
    /** IANA zone the event was authored in; null for all-day (UTC by contract) and floating times. */
    val timezone: String? = null,
    /** RRULE body without the `RRULE:` prefix, matching how the provider stores it. */
    val rrule: String? = null,
    /** Reminder offsets in minutes before [start], from VALARM TRIGGERs. */
    val reminderMinutes: List<Int> = emptyList(),
    val uid: String? = null,
    /**
     * Set when this VEVENT overrides a single occurrence of the series sharing its [uid] rather
     * than describing a series of its own (RFC 5545 §3.8.4.4, RECURRENCE-ID). Such a VEVENT
     * carries no RRULE; it restates the occurrence's *original* start so the reader knows which
     * one it replaces.
     */
    val recurrenceId: Instant? = null,
    /**
     * Whether [recurrenceId] identifies its occurrence by date rather than by instant. It tracks
     * the *series'* value type, which an override may not change even when it changes its own —
     * so it is a separate flag and not [allDay].
     */
    val recurrenceIdAllDay: Boolean = false,
    /** Occurrence starts cancelled from this series (EXDATE). Empty for overrides and non-series. */
    val exdates: List<Instant> = emptyList(),
    /** The event's ORGANIZER, if it names one. Never repeated in [attendees]. */
    val organizer: Attendee? = null,
    /** ATTENDEE rows, organizer excluded. */
    val attendees: List<Attendee> = emptyList(),
    /**
     * `RANGE=THISANDFUTURE` on the RECURRENCE-ID: this override replaces its occurrence and every
     * later one, not just the one.
     */
    val thisAndFuture: Boolean = false,
    /** Extra occurrence starts (RDATE), each lasting as long as the event itself. */
    val rdates: List<Instant> = emptyList(),
    /** CLASS, or null when the file does not say. */
    val access: EventAccess? = null,
    /** TRANSP, or null when the file does not say. */
    val availability: EventAvailability? = null,
) {
    /** Whether this VEVENT replaces one occurrence of another VEVENT with the same [uid]. */
    val isOverride: Boolean get() = recurrenceId != null
}

/**
 * Minimal RFC 5545 reader/writer covering the subset Foscal models.
 *
 * Deliberately hand-rolled rather than pulled from a library: the app is GPLv3 and FOSS-only, the
 * needed subset is small, and keeping it in `:core-model` means it is plain JVM code that unit
 * tests can exercise without an emulator.
 */
object Ics {

    const val MIME_TYPE = "text/calendar"

    private const val CRLF = "\r\n"

    /**
     * RFC 5545 §3.1: content lines are folded at 75 *octets*, not characters. Folding by character
     * would overflow the limit for any non-ASCII summary, which some parsers reject outright.
     */
    private const val MAX_LINE_OCTETS = 75

    private val dateTimeUtc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val dateTimeLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * `ZoneId.of("UTC")`, not `ZoneOffset.UTC`, whose id is "Z". The provider stores "Z" and even
     * expands it correctly, but only because `java.util.TimeZone` falls back to GMT for an id it
     * does not know. "UTC" is the value AOSP itself writes (`Time.TIMEZONE_UTC`).
     */
    private val UTC: ZoneId = ZoneId.of("UTC")

    /**
     * Longest VALARM offset kept, 4 weeks. A reminder further out than that is almost always a
     * mistake in the file, and the reminder sync has to expand every occurrence up to the largest
     * offset: one `-P520W` alarm made it read ten years of events on every pass.
     */
    const val MAX_REMINDER_MINUTES = 4 * 7 * 24 * 60

    // ---------------------------------------------------------------- writing

    /**
     * Serializes [events] into a complete VCALENDAR document.
     *
     * A timed event whose zone has DST is written as wall time with a `TZID`, and the file carries
     * a VTIMEZONE for every such zone. A UTC start plus an RRULE repeats at a fixed UTC time, so a
     * weekly 10:00 Vienna series came back at 09:00 once winter time began. Events in UTC, in a
     * fixed offset or in a zone nothing can parse stay in UTC, which is exact for them.
     */
    fun write(events: List<IcsEvent>, stamp: Instant = Instant.now()): String {
        val sb = StringBuilder()
        sb.line("BEGIN:VCALENDAR")
        sb.line("VERSION:2.0")
        sb.line("PRODID:-//Foscal//Foscal Calendar//EN")
        sb.line("CALSCALE:GREGORIAN")
        val zones = events.filterNot { it.allDay }
            .mapNotNull { IcsZoneWriter.namedZone(it.timezone) }
            .distinctBy { it.id }
        for (zone in zones) IcsZoneWriter.lines(zone).forEach { sb.line(it) }
        val stampValue = stamp.atZone(ZoneOffset.UTC).format(dateTimeUtc)
        for (event in events) {
            val zone = if (event.allDay) null else IcsZoneWriter.namedZone(event.timezone)
            sb.line("BEGIN:VEVENT")
            sb.line("UID:${event.uid ?: syntheticUid(event)}")
            sb.line("DTSTAMP:$stampValue")
            event.recurrenceId?.let { occurrence ->
                if (event.recurrenceIdAllDay) {
                    sb.line("RECURRENCE-ID;VALUE=DATE:${utcDate(occurrence)}")
                } else {
                    sb.line(timeProperty("RECURRENCE-ID", listOf(occurrence), zone))
                }
            }
            if (event.allDay) {
                sb.line("DTSTART;VALUE=DATE:${utcDate(event.start)}")
                // RFC 5545 §3.8.2.2 requires a DATE-valued DTEND to be later than DTSTART, and the
                // exclusive end means equal dates describe an event covering no days at all. The
                // provider does hold such rows — an all-day event written with DTEND = DTSTART —
                // so clamp to a one-day span rather than emitting a VEVENT other apps will reject.
                val endDate = maxOf(utcLocalDate(event.end), utcLocalDate(event.start).plusDays(1))
                sb.line("DTEND;VALUE=DATE:${endDate.format(BASIC_ISO_DATE)}")
            } else {
                sb.line(timeProperty("DTSTART", listOf(event.start), zone))
                sb.line(timeProperty("DTEND", listOf(event.end), zone))
            }
            sb.line("SUMMARY:${escape(event.title)}")
            event.location?.takeIf { it.isNotBlank() }
                ?.let { sb.line("LOCATION:${escape(it)}") }
            event.description?.takeIf { it.isNotBlank() }
                ?.let { sb.line("DESCRIPTION:${escape(it)}") }
            event.rrule?.takeIf { it.isNotBlank() }
                ?.let { sb.line("RRULE:${it.removePrefix("RRULE:")}") }
            event.organizer?.let { sb.line(organizerLine(it)) }
            for (attendee in event.attendees) sb.line(attendeeLine(attendee))
            // Cancelled occurrences ride on the master as EXDATE rather than as their own
            // STATUS:CANCELLED override: every RFC 5545 reader understands EXDATE, while a
            // cancelled override is routinely imported as a real (if cancelled) event.
            val exdates = event.exdates.distinct().sorted()
            if (exdates.isNotEmpty()) {
                if (event.allDay) {
                    sb.line("EXDATE;VALUE=DATE:${exdates.joinToString(",") { utcDate(it) }}")
                } else {
                    sb.line(timeProperty("EXDATE", exdates, zone))
                }
            }
            for (minutes in event.reminderMinutes.distinct().sorted()) {
                sb.line("BEGIN:VALARM")
                sb.line("ACTION:DISPLAY")
                sb.line("DESCRIPTION:${escape(event.title)}")
                sb.line("TRIGGER;RELATED=START:${triggerDuration(minutes)}")
                sb.line("END:VALARM")
            }
            sb.line("END:VEVENT")
        }
        sb.line("END:VCALENDAR")
        return sb.toString()
    }

    /** [name] with [instants] as UTC values, or as wall times in [zone] when there is one. */
    private fun timeProperty(name: String, instants: List<Instant>, zone: ZoneId?): String =
        if (zone == null) {
            "$name:" + instants.joinToString(",") { it.atZone(ZoneOffset.UTC).format(dateTimeUtc) }
        } else {
            "$name;TZID=${zone.id}:" + instants.joinToString(",") { it.atZone(zone).format(dateTimeLocal) }
        }

    private fun utcDate(instant: Instant): String =
        utcLocalDate(instant).format(BASIC_ISO_DATE)

    /** All-day events are stored against UTC midnight, so their date must be read back in UTC. */
    private fun utcLocalDate(instant: Instant): LocalDate =
        instant.atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * A UID is mandatory, so events that never had one (anything Foscal created on a local
     * calendar) get a deterministic stand-in: re-exporting the same event twice must not look like
     * two different events to whatever imports the file.
     *
     * Public because an override VEVENT must carry its *master's* UID — and this derives one from
     * the title and start, which an override is free to change. Callers holding a series and its
     * overrides compute the master's uid once and copy it onto each override; letting [write] fall
     * back per event would hand every override a UID of its own and orphan it.
     */
    fun syntheticUid(event: IcsEvent): String =
        "${event.start.toEpochMilli()}-${abs(event.title.hashCode())}@foscal.app"

    private fun organizerLine(organizer: Attendee): String =
        "ORGANIZER${cnParam(organizer.name)}:mailto:${organizer.email}"

    private fun attendeeLine(attendee: Attendee): String = buildString {
        append("ATTENDEE")
        append(cnParam(attendee.name))
        append(";ROLE=")
        append(if (attendee.optional) "OPT-PARTICIPANT" else "REQ-PARTICIPANT")
        append(";PARTSTAT=")
        append(partstat(attendee.status))
        // RSVP is a request for an answer, so it only makes sense while there isn't one.
        if (attendee.status == AttendeeStatus.INVITED) append(";RSVP=TRUE")
        append(":mailto:")
        append(attendee.email)
    }

    /**
     * `;CN="Name"`, or empty when there is no name.
     *
     * The value is always quoted so a name containing `;`, `:` or `,` cannot end the parameter
     * early. RFC 5545 §3.1 gives a quoted parameter value no escape mechanism at all — a `"` inside
     * one simply terminates it — so an embedded quote is dropped rather than emitted broken.
     */
    private fun cnParam(name: String?): String {
        val clean = name?.filter { it != '"' && it != '\r' && it != '\n' }?.trim().orEmpty()
        return if (clean.isEmpty()) "" else ";CN=\"$clean\""
    }

    private fun partstat(status: AttendeeStatus): String = when (status) {
        AttendeeStatus.ACCEPTED -> "ACCEPTED"
        AttendeeStatus.DECLINED -> "DECLINED"
        AttendeeStatus.TENTATIVE -> "TENTATIVE"
        AttendeeStatus.INVITED -> "NEEDS-ACTION"
    }

    private fun triggerDuration(minutes: Int): String {
        if (minutes <= 0) return "PT0S"
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return buildString {
            append("-P")
            if (days > 0) append("${days}D")
            if (hours > 0 || mins > 0 || days == 0) {
                append("T")
                if (hours > 0) append("${hours}H")
                if (mins > 0 || (hours == 0 && days == 0)) append("${mins}M")
            }
        }
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun StringBuilder.line(content: String) {
        var used = 0
        var i = 0
        while (i < content.length) {
            val cp = content.codePointAt(i)
            val width = Character.charCount(cp)
            val octets = utf8Length(cp)
            if (used + octets > MAX_LINE_OCTETS) {
                append(CRLF).append(' ')
                // The leading space of a continuation line counts against its own budget.
                used = 1
            }
            append(content, i, i + width)
            used += octets
            i += width
        }
        append(CRLF)
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    // ---------------------------------------------------------------- reading

    /**
     * What [readDocument] found: the events it could build, and how many VEVENTs it could not.
     *
     * [rejected] counts VEVENTs with no usable DTSTART, so an import can report them as skipped
     * instead of letting them vanish from the file without a trace.
     */
    data class Document(val events: List<IcsEvent>, val rejected: Int)

    /** The events in [text]; see [readDocument]. */
    fun read(text: String, fallbackZone: ZoneId = ZoneId.systemDefault()): List<IcsEvent> =
        readDocument(text, fallbackZone).events

    /**
     * Parses every VEVENT in [text]. Unknown components (VTODO, VJOURNAL, VFREEBUSY) and unknown
     * properties are skipped rather than treated as errors — an `.ics` export from another app
     * routinely carries far more than this subset, and dropping the whole file over one unmodelled
     * property would make import useless in practice.
     *
     * [fallbackZone] resolves floating times (no TZID, no trailing `Z`) and TZIDs that nothing
     * resolves, unless the calendar names its own zone in `X-WR-TIMEZONE`, as Google's exports do.
     *
     * Cancellations are applied here, so callers only ever see what the source app would show: a
     * cancelled event or series is left out, a cancelled occurrence becomes an EXDATE on its
     * series, and a cancelled `THISANDFUTURE` occurrence ends the series before it.
     */
    fun readDocument(text: String, fallbackZone: ZoneId = ZoneId.systemDefault()): Document {
        val lines = unfold(text).mapNotNull(::parseContentLine)
        val (definitions, calendarZone) = readCalendarLevel(lines)
        val zones = IcsTimeZones(definitions)
        val namedZone = calendarZone?.let { zones.resolve(it, LocalDate.now().year) }
        val fallback = namedZone ?: fallbackZone

        val events = mutableListOf<Parsed>()
        var rejected = 0
        val stack = ArrayDeque<String>()
        var draft: Draft? = null
        var triggerMinutes: Int? = null
        var alarmAction: String? = null

        for (line in lines) {
            when (line.name) {
                "BEGIN" -> {
                    val component = line.value.trim().uppercase()
                    stack.addLast(component)
                    when (component) {
                        "VEVENT" -> draft = Draft(zones)
                        "VALARM" -> {
                            triggerMinutes = null
                            alarmAction = null
                        }
                    }
                }

                "END" -> {
                    val component = line.value.trim().uppercase()
                    when (component) {
                        "VEVENT" -> {
                            val built = draft?.build(fallback, namedZone)
                            if (built != null) events += built else if (draft != null) rejected++
                            draft = null
                        }

                        "VALARM" -> {
                            // EMAIL and PROCEDURE alarms are the server's to deliver. A local
                            // notification for one would duplicate a message the user already gets.
                            val local = alarmAction == null || alarmAction in LOCAL_ALARM_ACTIONS
                            val minutes = triggerMinutes
                            if (local && minutes != null && minutes <= MAX_REMINDER_MINUTES) {
                                draft?.reminders?.add(minutes)
                            }
                            triggerMinutes = null
                            alarmAction = null
                        }
                    }
                    if (stack.lastOrNull() == component) stack.removeLast()
                }

                else -> {
                    val current = draft ?: continue
                    when (stack.lastOrNull()) {
                        // Guarding on the innermost component is what keeps VTIMEZONE's own
                        // DTSTART (inside STANDARD/DAYLIGHT) from overwriting the event's.
                        "VEVENT" -> current.property(line)
                        "VALARM" -> when (line.name) {
                            "TRIGGER" -> triggerMinutes = parseTrigger(line)
                            "ACTION" -> alarmAction = line.value.trim().uppercase()
                        }
                    }
                }
            }
        }
        return Document(applyCancellations(events), rejected)
    }

    private val LOCAL_ALARM_ACTIONS = setOf("DISPLAY", "AUDIO")

    /**
     * The VTIMEZONE definitions and `X-WR-TIMEZONE`, read before any VEVENT because a file may
     * define its zones after the events that use them.
     */
    private fun readCalendarLevel(lines: List<ContentLine>): Pair<Map<String, VTimeZone>, String?> {
        val definitions = mutableMapOf<String, VTimeZone>()
        var calendarZone: String? = null
        val stack = ArrayDeque<String>()
        var tzid: String? = null
        var location: String? = null
        val observances = mutableListOf<VTimeZone.Observance>()
        var observance: MutableMap<String, ContentLine>? = null

        for (line in lines) {
            when (line.name) {
                "BEGIN" -> {
                    val component = line.value.trim().uppercase()
                    stack.addLast(component)
                    when (component) {
                        "VTIMEZONE" -> {
                            tzid = null
                            location = null
                            observances.clear()
                        }
                        "STANDARD", "DAYLIGHT" -> observance = mutableMapOf()
                    }
                }

                "END" -> {
                    val component = line.value.trim().uppercase()
                    when (component) {
                        "STANDARD", "DAYLIGHT" -> {
                            observance?.let { parseObservance(it, daylight = component == "DAYLIGHT") }
                                ?.let { observances += it }
                            observance = null
                        }

                        "VTIMEZONE" -> tzid?.let {
                            definitions[it] = VTimeZone(it, location, observances.toList())
                        }
                    }
                    if (stack.lastOrNull() == component) stack.removeLast()
                }

                else -> when (stack.lastOrNull()) {
                    "VCALENDAR" -> if (line.name == "X-WR-TIMEZONE") {
                        calendarZone = line.value.trim().takeIf { it.isNotEmpty() }
                    }
                    "VTIMEZONE" -> when (line.name) {
                        "TZID" -> tzid = line.value.trim().trim('"').takeIf { it.isNotEmpty() }
                        "X-LIC-LOCATION" -> location = line.value.trim()
                    }
                    "STANDARD", "DAYLIGHT" -> observance?.put(line.name, line)
                }
            }
        }
        return definitions to calendarZone
    }

    private fun parseObservance(props: Map<String, ContentLine>, daylight: Boolean): VTimeZone.Observance? {
        val start = props["DTSTART"]?.value?.trim()
            ?.let { runCatching { LocalDateTime.parse(it.take(15), dateTimeLocal) }.getOrNull() }
            ?: return null
        val offsetTo = props["TZOFFSETTO"]?.value?.let(::parseOffset) ?: return null
        val offsetFrom = props["TZOFFSETFROM"]?.value?.let(::parseOffset) ?: offsetTo
        val rule = props["RRULE"]?.value.orEmpty().split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.take(eq).trim().uppercase() to part.substring(eq + 1).trim()
        }.toMap()
        val byDay = rule["BYDAY"]?.let { Regex("^([+-]?\\d+)?([A-Z]{2})$").find(it.uppercase()) }
        val ordinal = byDay?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            // A bare weekday with BYMONTHDAY=8..14 is "the second one", which Outlook writes too.
            ?: rule["BYMONTHDAY"]?.split(',')?.firstOrNull()?.toIntOrNull()?.let { (it - 1) / 7 + 1 }
        return VTimeZone.Observance(
            daylight = daylight,
            start = start,
            offsetFrom = offsetFrom,
            offsetTo = offsetTo,
            month = rule["BYMONTH"]?.toIntOrNull(),
            weekOrdinal = ordinal,
            dayOfWeek = byDay?.groupValues?.get(2)?.let(::parseWeekday),
        )
    }

    private fun parseOffset(text: String): ZoneOffset? {
        val raw = text.trim()
        val sign = raw.firstOrNull()?.takeIf { it == '+' || it == '-' } ?: return null
        val digits = raw.drop(1)
        if (digits.length != 4 && digits.length != 6) return null
        return runCatching {
            ZoneOffset.of("$sign${digits.substring(0, 2)}:${digits.substring(2, 4)}" +
                if (digits.length == 6) ":${digits.substring(4, 6)}" else "")
        }.getOrNull()
    }

    private fun parseWeekday(code: String): java.time.DayOfWeek? = when (code) {
        "MO" -> java.time.DayOfWeek.MONDAY
        "TU" -> java.time.DayOfWeek.TUESDAY
        "WE" -> java.time.DayOfWeek.WEDNESDAY
        "TH" -> java.time.DayOfWeek.THURSDAY
        "FR" -> java.time.DayOfWeek.FRIDAY
        "SA" -> java.time.DayOfWeek.SATURDAY
        "SU" -> java.time.DayOfWeek.SUNDAY
        else -> null
    }

    /**
     * Applies STATUS:CANCELLED the way calendar apps display it.
     *
     * A cancelled override is folded into its series as an EXDATE, or as an end to the series when
     * it carries `RANGE=THISANDFUTURE`. A cancelled series takes its overrides with it, and a
     * cancelled one-off event is left out.
     */
    private fun applyCancellations(parsed: List<Parsed>): List<IcsEvent> {
        val cancelled = parsed.filter { it.cancelled }.map { it.event }
        val cancelledSeries = cancelled.filter { !it.isOverride }.mapNotNull { it.uid }.toSet()
        val cancelledOverrides = cancelled
            .filter { it.isOverride && it.uid != null && it.uid !in cancelledSeries }
            .groupBy { it.uid!! }
        return parsed
            .filterNot { it.cancelled }
            .map { it.event }
            .filterNot { it.isOverride && it.uid in cancelledSeries }
            .map { event ->
                val cancellations = event.uid?.takeIf { !event.isOverride }?.let { cancelledOverrides[it] }
                    ?: return@map event
                val (following, single) = cancellations.partition { it.thisAndFuture }
                val end = following.mapNotNull { it.recurrenceId }.minOrNull()
                event.copy(
                    rrule = end?.let { RecurrenceRules.truncateBefore(event.rrule, it, event.allDay) }
                        ?: event.rrule,
                    exdates = (event.exdates + single.mapNotNull { it.recurrenceId })
                        .filter { end == null || it < end }
                        .distinct()
                        .sorted(),
                )
            }
    }

    /** A built VEVENT and whether it said STATUS:CANCELLED, which [applyCancellations] resolves. */
    private class Parsed(val event: IcsEvent, val cancelled: Boolean)

    private class Draft(private val zones: IcsTimeZones) {
        var uid: String? = null
        var title: String? = null
        var location: String? = null
        var description: String? = null
        var start: DateValue? = null
        var end: DateValue? = null
        var duration: Long? = null
        var rrule: String? = null
        var recurrenceId: DateValue? = null
        var thisAndFuture = false
        var cancelled = false
        var access: EventAccess? = null
        var availability: EventAvailability? = null
        var organizer: Attendee? = null
        val exdates = mutableListOf<DateValue>()
        val rdates = mutableListOf<DateValue>()
        val reminders = mutableListOf<Int>()
        val attendees = mutableListOf<Attendee>()

        fun property(line: ContentLine) {
            when (line.name) {
                "ORGANIZER" -> organizer = parseAttendee(line, isOrganizer = true)
                "ATTENDEE" -> parseAttendee(line, isOrganizer = false)?.let { attendees += it }
                "RECURRENCE-ID" -> {
                    recurrenceId = parseDateToken(line.value, line.params, zones)
                    thisAndFuture = line.params["RANGE"]?.equals("THISANDFUTURE", ignoreCase = true) == true
                }
                // EXDATE and RDATE are multi-valued: one property can carry a whole comma-separated
                // list, and a VEVENT may repeat the property as well. Both forms accumulate.
                "EXDATE" -> exdates += splitUnquoted(line.value, ',')
                    .mapNotNull { parseDateToken(it, line.params, zones) }
                // A PERIOD value is "start/end" or "start/duration"; only its start is kept, and the
                // occurrence lasts as long as the event itself.
                "RDATE" -> rdates += splitUnquoted(line.value, ',')
                    .mapNotNull { parseDateToken(it.substringBefore('/'), line.params - "VALUE", zones) }
                "UID" -> uid = line.value.trim().takeIf { it.isNotEmpty() }
                "SUMMARY" -> title = unescape(line.value)
                "LOCATION" -> location = unescape(line.value).takeIf { it.isNotBlank() }
                "DESCRIPTION" -> description = unescape(line.value).takeIf { it.isNotBlank() }
                "DTSTART" -> start = parseDateToken(line.value, line.params, zones)
                "DTEND" -> end = parseDateToken(line.value, line.params, zones)
                "DURATION" -> duration = parseDuration(line.value)
                "RRULE" -> rrule = line.value.trim().takeIf { it.isNotBlank() }
                "STATUS" -> cancelled = line.value.trim().equals("CANCELLED", ignoreCase = true)
                "CLASS" -> access = when (line.value.trim().uppercase()) {
                    "PUBLIC" -> EventAccess.PUBLIC
                    "PRIVATE" -> EventAccess.PRIVATE
                    "CONFIDENTIAL" -> EventAccess.CONFIDENTIAL
                    else -> null
                }
                "TRANSP" -> availability = when (line.value.trim().uppercase()) {
                    "OPAQUE" -> EventAvailability.BUSY
                    "TRANSPARENT" -> EventAvailability.FREE
                    else -> null
                }
            }
        }

        /** [calendarZone] is `X-WR-TIMEZONE`, which a floating time then reports as its zone. */
        fun build(fallbackZone: ZoneId, calendarZone: ZoneId?): Parsed? {
            val startValue = start ?: return null
            val allDay = startValue.dateOnly
            val startInstant = startValue.toInstant(fallbackZone)
            val endInstant = when {
                end != null -> end!!.toInstant(fallbackZone)
                duration != null -> startInstant.plusMillis(duration!!)
                // RFC 5545 §3.6.1: a DATE-valued DTSTART with no DTEND/DURATION lasts one day; a
                // DATE-TIME one is zero-length. Reproducing that here keeps an all-day event from
                // importing as an empty span the views would refuse to draw.
                allDay -> startInstant.plus(java.time.Duration.ofDays(1))
                else -> startInstant
            }
            // A DATE in EXDATE or RDATE on a timed series names a day, and the occurrence it means
            // is the one at the series' own wall time that day. Read as UTC midnight it matched
            // no occurrence at all, so the exclusion silently did nothing.
            val onSeriesDay = { value: DateValue ->
                if (value.dateOnly && !allDay) {
                    value.date.atTime(startValue.time!!.toLocalTime())
                        .atZone(startValue.zone ?: fallbackZone).toInstant()
                } else {
                    value.toInstant(fallbackZone)
                }
            }
            val event = IcsEvent(
                title = title?.takeIf { it.isNotBlank() } ?: "(No title)",
                start = startInstant,
                end = if (endInstant.isBefore(startInstant)) startInstant else endInstant,
                allDay = allDay,
                location = location,
                description = description,
                timezone = if (allDay) null else (startValue.zone ?: calendarZone)?.id,
                // An override describes one occurrence; any RRULE on it would be a second series.
                rrule = if (recurrenceId != null) null else rrule,
                reminderMinutes = reminders.distinct().sorted(),
                uid = uid,
                recurrenceId = recurrenceId?.toInstant(fallbackZone),
                recurrenceIdAllDay = recurrenceId?.dateOnly == true,
                exdates = exdates.map(onSeriesDay).distinct().sorted(),
                organizer = organizer,
                // Most exporters list the organizer as an ATTENDEE too, so that they get an entry in
                // the guest list alongside the answer they gave. Keeping both would show the same
                // person twice; the ATTENDEE row is the one dropped because ORGANIZER is what says
                // which role they hold. Matched through Attendee.normalizeAddress, because an
                // iCalendar address is a URI: the same person can arrive as `mailto:a@b` on one
                // property and `a@b` on the other, and a raw comparison lists them twice.
                attendees = attendees
                    .distinctBy { Attendee.normalizeAddress(it.email) }
                    .filterNot { attendee ->
                        organizer?.let {
                            Attendee.normalizeAddress(attendee.email) ==
                                Attendee.normalizeAddress(it.email)
                        } == true
                    },
                thisAndFuture = recurrenceId != null && thisAndFuture,
                rdates = rdates.map(onSeriesDay).filter { it != startInstant }.distinct().sorted(),
                access = access,
                availability = availability,
            )
            return Parsed(event, cancelled)
        }
    }

    /**
     * One ORGANIZER / ATTENDEE property, or null when it carries no usable address.
     *
     * The value is a CAL-ADDRESS — in practice always `mailto:` — and everything else about the
     * person rides in parameters. A row without an address cannot be matched to anyone by either
     * the provider or a `mailto:` intent, so it is dropped rather than stored nameless.
     */
    private fun parseAttendee(line: ContentLine, isOrganizer: Boolean): Attendee? {
        val email = line.value.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim()
        if (email.isEmpty()) return null
        return Attendee(
            email = email,
            name = line.params["CN"]?.trim()?.takeIf { it.isNotEmpty() },
            status = when (line.params["PARTSTAT"]?.uppercase()) {
                "ACCEPTED" -> AttendeeStatus.ACCEPTED
                "DECLINED" -> AttendeeStatus.DECLINED
                "TENTATIVE" -> AttendeeStatus.TENTATIVE
                else -> AttendeeStatus.INVITED
            },
            isOrganizer = isOrganizer,
            optional = line.params["ROLE"]?.uppercase() == "OPT-PARTICIPANT",
        )
    }

    /**
     * A parsed DTSTART/DTEND: either a floating/zoned wall time or a pure date.
     *
     * All-day values resolve at UTC midnight, matching how the Calendar Provider stores them —
     * resolving them in the device zone shifts them a day west of UTC.
     */
    private class DateValue(
        val dateOnly: Boolean,
        val date: LocalDate,
        val time: LocalDateTime?,
        val zone: ZoneId?,
        val utc: Boolean,
    ) {
        fun toInstant(fallbackZone: ZoneId): Instant = when {
            dateOnly -> date.atStartOfDay(ZoneOffset.UTC).toInstant()
            utc -> time!!.toInstant(ZoneOffset.UTC)
            else -> time!!.atZone(zone ?: fallbackZone).toInstant()
        }
    }

    /** One DATE / DATE-TIME value, which for a multi-valued property is one item of its list. */
    private fun parseDateToken(raw: String, params: Map<String, String>, zones: IcsTimeZones): DateValue? {
        val value = raw.trim()
        val dateOnly = params["VALUE"]?.equals("DATE", ignoreCase = true) == true ||
            (value.length == 8 && 'T' !in value)
        return runCatching {
            if (dateOnly) {
                DateValue(true, LocalDate.parse(value.take(8), BASIC_ISO_DATE), null, null, false)
            } else {
                val utc = value.endsWith("Z", ignoreCase = true)
                val core = if (utc) value.dropLast(1) else value
                val time = LocalDateTime.parse(core, dateTimeLocal)
                val zone = params["TZID"]?.let { zones.resolve(it, time.year) }
                // A trailing Z states the zone as surely as a TZID does. Reporting it as "no zone"
                // would make the caller fall back to the device zone, which re-anchors a recurring
                // series' wall time and shifts every occurrence across a DST boundary.
                DateValue(false, time.toLocalDate(), time, zone ?: if (utc) UTC else null, utc)
            }
        }.getOrNull()
    }

    /**
     * Minutes *before* the event a VALARM fires, or null if the alarm cannot be represented.
     *
     * Only START-relative negative (or zero) durations survive. An absolute DATE-TIME trigger has
     * no fixed relationship to a recurring series' occurrences, and END-relative or positive
     * triggers fire at or after the event — the provider's `Reminders.MINUTES` cannot express
     * either, and inventing an offset would silently move the user's alarm.
     */
    private fun parseTrigger(line: ContentLine): Int? {
        if (line.params["VALUE"]?.equals("DATE-TIME", ignoreCase = true) == true) return null
        val related = line.params["RELATED"]
        if (related != null && !related.equals("START", ignoreCase = true)) return null
        val millis = parseDuration(line.value) ?: return null
        if (millis > 0L) return null
        return (-millis / 60_000L).toInt()
    }

    /**
     * ISO 8601 duration in milliseconds, signed, or null if [text] is not one.
     *
     * Public because the Calendar Provider stores a recurring event's length in the same format in
     * `Events.DURATION` (it forbids DTEND there), so reading a master event row needs this too.
     * `java.time.Duration.parse` is not a substitute: it rejects the week form (`P1W`), which the
     * provider and other calendar apps both emit.
     */
    fun parseDuration(text: String): Long? {
        val raw = text.trim().uppercase()
        if (raw.isEmpty()) return null
        val negative = raw.startsWith("-")
        val body = raw.removePrefix("-").removePrefix("+")
        if (!body.startsWith("P")) return null
        var total = 0L
        var number = StringBuilder()
        var seen = false
        for (c in body.drop(1)) {
            when {
                c.isDigit() -> number.append(c)
                c == 'T' -> Unit
                else -> {
                    val amount = number.toString().toLongOrNull() ?: return null
                    number = StringBuilder()
                    total += when (c) {
                        'W' -> amount * 7 * 86_400_000L
                        'D' -> amount * 86_400_000L
                        'H' -> amount * 3_600_000L
                        'M' -> amount * 60_000L
                        'S' -> amount * 1_000L
                        else -> return null
                    }
                    seen = true
                }
            }
        }
        if (!seen) return null
        return if (negative) -total else total
    }

    private fun unescape(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n', 'N' -> append('\n')
                    '\\', ';', ',' -> append(next)
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private class ContentLine(
        val name: String,
        val params: Map<String, String>,
        val value: String,
    )

    private fun parseContentLine(line: String): ContentLine? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            // Parameter values may be quoted and a quoted value may itself contain ':' — a naive
            // indexOf(':') splits TZID="Europe/Berlin: standard" in the wrong place.
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segments = splitUnquoted(head, ';')
        val name = segments.firstOrNull()?.trim()?.uppercase().orEmpty()
        if (name.isEmpty()) return null
        val params = segments.drop(1).mapNotNull { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) null
            else segment.take(eq).trim().uppercase() to segment.substring(eq + 1).trim().trim('"')
        }.toMap()
        return ContentLine(name, params, value)
    }

    private fun splitUnquoted(text: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    current.append(c)
                }

                c == delimiter && !quoted -> {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(c)
            }
        }
        out += current.toString()
        return out
    }

    /**
     * Splits [text] into logical lines, re-joining RFC 5545 folded continuations (any line starting
     * with a space or tab belongs to the previous one).
     */
    private fun unfold(text: String): List<String> {
        val body = text.removePrefix("﻿")
        val out = mutableListOf<StringBuilder>()
        for (line in body.split("\r\n", "\n", "\r")) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
                out.last().append(line.substring(1))
            } else {
                out += StringBuilder(line)
            }
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }
}
