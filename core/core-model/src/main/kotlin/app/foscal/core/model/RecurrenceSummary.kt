package app.foscal.core.model

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** One line of text that says how an RRULE repeats, for the event detail screen. */
object RecurrenceSummary {

    /** What a rule the app cannot describe in full is called, instead of a partial summary. */
    const val CUSTOM = "Custom repeat"

    /**
     * "Weekly", "Every 2 weeks on Mon, Wed", "Monthly, 10 times", "Weekly until 3 Nov 2026".
     *
     * A rule with parts this app does not model reads [CUSTOM], because a summary of the rest would
     * be wrong: the last Friday of every month is not "Monthly".
     *
     * @param zone the zone a timed UNTIL is read in, so the date shown is the one the user picked.
     * @param locale for weekday names, the order of the week and the UNTIL date.
     */
    fun describe(rrule: String, zone: ZoneId, locale: Locale): String {
        if (!RecurrenceRules.isModelled(rrule)) return CUSTOM
        val spec = RecurrenceRules.parse(rrule, zone)
        val n = spec.interval
        val base = when (spec.frequency) {
            Frequency.DAILY -> if (n == 1) "Daily" else "Every $n days"
            Frequency.WEEKLY -> if (n == 1) "Weekly" else "Every $n weeks"
            Frequency.MONTHLY -> if (n == 1) "Monthly" else "Every $n months"
            Frequency.YEARLY -> if (n == 1) "Yearly" else "Every $n years"
            Frequency.NONE -> return CUSTOM
        }
        val days = if (spec.frequency == Frequency.WEEKLY && spec.byWeekday.isNotEmpty()) {
            val first = WeekFields.of(locale).firstDayOfWeek
            val ordered = (0L until 7L).map { first.plus(it) }.filter { it in spec.byWeekday }
            " on " + ordered.joinToString(", ") { it.shortName(locale) }
        } else {
            ""
        }
        val end = when {
            spec.count == 1 -> ", once"
            spec.count != null -> ", ${spec.count} times"
            spec.until != null -> " until " +
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                    .format(spec.until)
            else -> ""
        }
        return base + days + end
    }

    private fun DayOfWeek.shortName(locale: Locale): String =
        getDisplayName(TextStyle.SHORT, locale)
}
