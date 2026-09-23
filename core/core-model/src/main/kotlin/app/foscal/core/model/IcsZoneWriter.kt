package app.foscal.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransitionRule
import kotlin.math.abs

/**
 * Writes the VTIMEZONE blocks that let a reader expand a `TZID`-anchored series at its wall time.
 *
 * Only the zone's *current* yearly rules are written, each starting in 1970, which is what
 * Google's exports and tzurl.org's Outlook-compatible definitions do. Outlook reads only one
 * STANDARD and one DAYLIGHT rule, so a block listing every historical transition confuses it.
 * Readers that know the IANA name use their own rules and ignore the block, so the only cost is
 * that Outlook places an occurrence from before the zone's last rule change by today's rules.
 */
internal object IcsZoneWriter {

    private val localDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** The year every observance claims to start in; see the class comment. */
    private const val EPOCH_YEAR = 1970

    /**
     * [id] as a zone worth naming in a `TZID`, or null when UTC says the same thing. A fixed offset
     * has no DST, so a UTC instant keeps its wall time forever, and an unparseable id has no rules
     * to write.
     */
    fun namedZone(id: String?): ZoneId? {
        val zone = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        return if (zone.rules.isFixedOffset) null else zone
    }

    fun lines(zone: ZoneId): List<String> = buildList {
        add("BEGIN:VTIMEZONE")
        add("TZID:${zone.id}")
        add("X-LIC-LOCATION:${zone.id}")
        val rules = zone.rules
        val yearly = rules.transitionRules
        if (yearly.isEmpty()) {
            // No DST any more: one observance at the offset the zone settled on.
            val offset = rules.transitions.lastOrNull()?.offsetAfter
                ?: rules.getOffset(LocalDateTime.of(EPOCH_YEAR, 1, 1, 0, 0))
            addAll(observance("STANDARD", LocalDateTime.of(EPOCH_YEAR, 1, 1, 0, 0), offset, offset, null))
        } else {
            for (rule in yearly) {
                val transition = rule.createTransition(EPOCH_YEAR)
                val kind = if (rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds) {
                    "DAYLIGHT"
                } else {
                    "STANDARD"
                }
                addAll(
                    observance(
                        kind,
                        transition.dateTimeBefore,
                        rule.offsetBefore,
                        rule.offsetAfter,
                        recurrence(rule),
                    ),
                )
            }
        }
        add("END:VTIMEZONE")
    }

    private fun observance(
        kind: String,
        start: LocalDateTime,
        from: ZoneOffset,
        to: ZoneOffset,
        rrule: String?,
    ): List<String> = listOfNotNull(
        "BEGIN:$kind",
        "DTSTART:${start.format(localDateTime)}",
        "TZOFFSETFROM:${offset(from)}",
        "TZOFFSETTO:${offset(to)}",
        rrule?.let { "RRULE:$it" },
        "END:$kind",
    )

    /**
     * `+hhmm`, or `+hhmmss` for the few historical offsets with seconds. Padded by hand because
     * `String.format` uses the default locale's digits, which on an Arabic phone are not ASCII.
     */
    fun offset(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val abs = abs(total)
        val two = { n: Int -> n.toString().padStart(2, '0') }
        val hhmm = two(abs / 3600) + two(abs / 60 % 60)
        return if (abs % 60 == 0) "$sign$hhmm" else sign + hhmm + two(abs % 60)
    }

    /**
     * The yearly RRULE that repeats [rule] on the dates its transitions actually fall on.
     *
     * The rule's own day is not always the transition's local date: a transition at 24:00, or one
     * defined in UTC, lands a day later or earlier. That shift is applied to the weekday and the
     * range of days it may fall on, so the RRULE names the date the wall clock changes.
     */
    fun recurrence(rule: ZoneOffsetTransitionRule): String {
        val indicator = rule.dayOfMonthIndicator
        val dow = rule.dayOfWeek
        val shift = ChronoUnit.DAYS.between(
            nominalDate(rule, EPOCH_YEAR),
            rule.createTransition(EPOCH_YEAR).dateTimeBefore.toLocalDate(),
        ).toInt()
        val base = "FREQ=YEARLY;BYMONTH=${rule.month.value}"
        if (dow == null) {
            if (indicator < 0 && shift == 0) return "$base;BYMONTHDAY=$indicator"
            val day = rule.createTransition(EPOCH_YEAR).dateTimeBefore.toLocalDate()
            return "FREQ=YEARLY;BYMONTH=${day.monthValue};BYMONTHDAY=${day.dayOfMonth}"
        }
        val weekday = code(dow.plus(shift.toLong()))
        // "On or after day d" and "on or before day -k" are each a seven-day window. Java writes
        // the EU's "last Sunday" as "Sunday on or after the 25th", so a window that ends on the
        // month's last day is turned back into -1SU, the form Outlook and Foscal's reader know.
        val month = rule.month
        val length = month.minLength()
        val fixedLength = month != Month.FEBRUARY
        val (first, last) = if (indicator > 0) {
            indicator + shift to indicator + shift + 6
        } else {
            // As positive days, so both directions share the checks below.
            length + 1 + indicator - 6 + shift to length + 1 + indicator + shift
        }
        return when {
            first >= 1 && last <= 28 && (first - 1) % 7 == 0 ->
                "$base;BYDAY=${(first - 1) / 7 + 1}$weekday"
            fixedLength && first >= 1 && last <= length && (length - last) % 7 == 0 ->
                "$base;BYDAY=-${(length - last) / 7 + 1}$weekday"
            indicator > 0 && first >= 1 && last <= length ->
                "$base;BYMONTHDAY=${(first..last).joinToString(",")};BYDAY=$weekday"
            indicator < 0 && first >= 1 && last <= length ->
                "$base;BYMONTHDAY=${(first..last).map { it - length - 1 }.joinToString(",")};BYDAY=$weekday"
            else -> yearDayWindow(rule.month, first, weekday)
        }
    }

    /**
     * A seven-day window that runs past its month's end, written as days of the year. Egypt's
     * "Friday after the last Thursday of October" is one. Counted from the year's end, so that a
     * leap day does not move it; only a window before March is counted from the start.
     */
    private fun yearDayWindow(month: Month, firstDay: Int, weekday: String): String {
        val start = LocalDate.of(EPOCH_YEAR, month, 1).plusDays((firstDay - 1).toLong())
        val days = (0..6).map { start.plusDays(it.toLong()) }
            .map { if (start.monthValue >= 3) it.dayOfYear - start.lengthOfYear() - 1 else it.dayOfYear }
        return "FREQ=YEARLY;BYYEARDAY=${days.joinToString(",")};BYDAY=$weekday"
    }

    /** The day [rule] names in [year], before any shift from its time of day. */
    private fun nominalDate(rule: ZoneOffsetTransitionRule, year: Int): LocalDate {
        val indicator = rule.dayOfMonthIndicator
        val month = LocalDate.of(year, rule.month, 1)
        val day = if (indicator > 0) {
            month.withDayOfMonth(indicator)
        } else {
            month.with(TemporalAdjusters.lastDayOfMonth()).plusDays((indicator + 1).toLong())
        }
        val dow = rule.dayOfWeek ?: return day
        return if (indicator > 0) {
            day.with(TemporalAdjusters.nextOrSame(dow))
        } else {
            day.with(TemporalAdjusters.previousOrSame(dow))
        }
    }

    private fun code(day: DayOfWeek): String = day.name.take(2)
}
