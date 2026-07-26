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
)

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

    /** `ZoneId.of("UTC")`, not `ZoneOffset.UTC`: the latter's id is "Z", which the provider's
     *  `EVENT_TIMEZONE` column does not accept. */
    private val UTC: ZoneId = ZoneId.of("UTC")

    // ---------------------------------------------------------------- writing

    /**
     * Serializes [events] into a complete VCALENDAR document.
     *
     * Timed events are written in UTC rather than with a TZID parameter. A TZID that does not
     * resolve on the reading side is only usable if the file also carries the matching VTIMEZONE
     * component with its full DST rules, and emitting a *wrong* VTIMEZONE (one STANDARD block with
     * today's offset) is worse than not emitting one: it silently shifts every occurrence on the
     * other side of a DST boundary. UTC instants are unambiguous everywhere. The trade-off is that
     * a recurring event exported across a DST change keeps its absolute time rather than its wall
     * time; [IcsEvent.timezone] is preserved on import so round-tripping through Foscal itself is
     * unaffected.
     */
    fun write(events: List<IcsEvent>, stamp: Instant = Instant.now()): String {
        val sb = StringBuilder()
        sb.line("BEGIN:VCALENDAR")
        sb.line("VERSION:2.0")
        sb.line("PRODID:-//Foscal//Foscal Calendar//EN")
        sb.line("CALSCALE:GREGORIAN")
        val stampValue = stamp.atZone(ZoneOffset.UTC).format(dateTimeUtc)
        for (event in events) {
            sb.line("BEGIN:VEVENT")
            sb.line("UID:${event.uid ?: syntheticUid(event)}")
            sb.line("DTSTAMP:$stampValue")
            if (event.allDay) {
                sb.line("DTSTART;VALUE=DATE:${utcDate(event.start)}")
                // RFC 5545 §3.8.2.2 requires a DATE-valued DTEND to be later than DTSTART, and the
                // exclusive end means equal dates describe an event covering no days at all. The
                // provider does hold such rows — an all-day event written with DTEND = DTSTART —
                // so clamp to a one-day span rather than emitting a VEVENT other apps will reject.
                val endDate = maxOf(utcLocalDate(event.end), utcLocalDate(event.start).plusDays(1))
                sb.line("DTEND;VALUE=DATE:${endDate.format(BASIC_ISO_DATE)}")
            } else {
                sb.line("DTSTART:${event.start.atZone(ZoneOffset.UTC).format(dateTimeUtc)}")
                sb.line("DTEND:${event.end.atZone(ZoneOffset.UTC).format(dateTimeUtc)}")
            }
            sb.line("SUMMARY:${escape(event.title)}")
            event.location?.takeIf { it.isNotBlank() }
                ?.let { sb.line("LOCATION:${escape(it)}") }
            event.description?.takeIf { it.isNotBlank() }
                ?.let { sb.line("DESCRIPTION:${escape(it)}") }
            event.rrule?.takeIf { it.isNotBlank() }
                ?.let { sb.line("RRULE:${it.removePrefix("RRULE:")}") }
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

    private fun utcDate(instant: Instant): String =
        utcLocalDate(instant).format(BASIC_ISO_DATE)

    /** All-day events are stored against UTC midnight, so their date must be read back in UTC. */
    private fun utcLocalDate(instant: Instant): LocalDate =
        instant.atZone(ZoneOffset.UTC).toLocalDate()

    /**
     * A UID is mandatory, so events that never had one (anything Foscal created on a local
     * calendar) get a deterministic stand-in: re-exporting the same event twice must not look like
     * two different events to whatever imports the file.
     */
    private fun syntheticUid(event: IcsEvent): String =
        "${event.start.toEpochMilli()}-${abs(event.title.hashCode())}@foscal.app"

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
     * Parses every VEVENT in [text]. Unknown components (VTODO, VJOURNAL, VFREEBUSY) and unknown
     * properties are skipped rather than treated as errors — an `.ics` export from another app
     * routinely carries far more than this subset, and dropping the whole file over one unmodelled
     * property would make import useless in practice.
     *
     * [fallbackZone] resolves floating times (no TZID, no trailing `Z`) and unknown TZIDs.
     */
    fun read(text: String, fallbackZone: ZoneId = ZoneId.systemDefault()): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()
        val stack = ArrayDeque<String>()
        var draft: Draft? = null
        var triggerMinutes: Int? = null

        for (raw in unfold(text)) {
            val line = parseContentLine(raw) ?: continue
            when (line.name) {
                "BEGIN" -> {
                    val component = line.value.trim().uppercase()
                    stack.addLast(component)
                    when (component) {
                        "VEVENT" -> draft = Draft()
                        "VALARM" -> triggerMinutes = null
                    }
                }

                "END" -> {
                    val component = line.value.trim().uppercase()
                    when (component) {
                        "VEVENT" -> {
                            draft?.build(fallbackZone)?.let { events += it }
                            draft = null
                        }

                        "VALARM" -> {
                            triggerMinutes?.let { draft?.reminders?.add(it) }
                            triggerMinutes = null
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
                        "VALARM" -> if (line.name == "TRIGGER") {
                            triggerMinutes = parseTrigger(line)
                        }
                    }
                }
            }
        }
        return events
    }

    private class Draft {
        var uid: String? = null
        var title: String? = null
        var location: String? = null
        var description: String? = null
        var start: DateValue? = null
        var end: DateValue? = null
        var duration: Long? = null
        var rrule: String? = null
        val reminders = mutableListOf<Int>()

        fun property(line: ContentLine) {
            when (line.name) {
                "UID" -> uid = line.value.trim().takeIf { it.isNotEmpty() }
                "SUMMARY" -> title = unescape(line.value)
                "LOCATION" -> location = unescape(line.value).takeIf { it.isNotBlank() }
                "DESCRIPTION" -> description = unescape(line.value).takeIf { it.isNotBlank() }
                "DTSTART" -> start = parseDateValue(line)
                "DTEND" -> end = parseDateValue(line)
                "DURATION" -> duration = parseDuration(line.value)
                "RRULE" -> rrule = line.value.trim().takeIf { it.isNotBlank() }
            }
        }

        fun build(fallbackZone: ZoneId): IcsEvent? {
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
            return IcsEvent(
                title = title?.takeIf { it.isNotBlank() } ?: "(No title)",
                start = startInstant,
                end = if (endInstant.isBefore(startInstant)) startInstant else endInstant,
                allDay = allDay,
                location = location,
                description = description,
                timezone = if (allDay) null else startValue.zone?.id,
                rrule = rrule,
                reminderMinutes = reminders.distinct().sorted(),
                uid = uid,
            )
        }
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

    private fun parseDateValue(line: ContentLine): DateValue? {
        val value = line.value.trim()
        val zone = line.params["TZID"]?.let { tzid ->
            runCatching { ZoneId.of(tzid.trim().trim('"')) }.getOrNull()
        }
        val dateOnly = line.params["VALUE"]?.equals("DATE", ignoreCase = true) == true ||
            (value.length == 8 && 'T' !in value)
        return runCatching {
            if (dateOnly) {
                DateValue(true, LocalDate.parse(value.take(8), BASIC_ISO_DATE), null, null, false)
            } else {
                val utc = value.endsWith("Z", ignoreCase = true)
                val core = if (utc) value.dropLast(1) else value
                val time = LocalDateTime.parse(core, dateTimeLocal)
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
