package app.foscal.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What the event detail screen says about how an RRULE repeats, before it is put into words.
 *
 * The words themselves are string resources in `:app`; this is the part that decides *what* to say
 * and can be tested without a device.
 */
data class RecurrenceSummary(
    val frequency: Frequency,
    val interval: Int,
    /** BYDAY of a weekly rule, in the order the locale's week runs. Empty for every other rule. */
    val weekdays: List<DayOfWeek>,
    val count: Int?,
    val until: LocalDate?,
) {
    companion object {

        /**
         * The summary of [rrule], or null when the rule has parts this app does not model. A
         * summary of the rest would be wrong: the last Friday of every month is not "Monthly", so
         * such a rule is shown as a custom repeat instead.
         *
         * @param zone the zone a timed UNTIL is read in, so the date shown is the one the user picked.
         * @param locale for the order of the week.
         */
        fun of(rrule: String, zone: ZoneId, locale: Locale): RecurrenceSummary? {
            if (!RecurrenceRules.isModelled(rrule)) return null
            val spec = RecurrenceRules.parse(rrule, zone)
            if (spec.frequency == Frequency.NONE) return null
            val weekdays = if (spec.frequency == Frequency.WEEKLY) {
                val first = WeekFields.of(locale).firstDayOfWeek
                (0L until 7L).map { first.plus(it) }.filter { it in spec.byWeekday }
            } else {
                emptyList()
            }
            return RecurrenceSummary(
                frequency = spec.frequency,
                interval = spec.interval,
                weekdays = weekdays,
                count = spec.count,
                until = spec.until,
            )
        }
    }
}
