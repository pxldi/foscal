package app.foscal.ui.util

import app.foscal.R
import app.foscal.core.model.Frequency
import app.foscal.core.model.RecurrenceSummary
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * One line that says how a series repeats: "Weekly", "Every 2 weeks on Mon, Wed", "Monthly, 10 times",
 * "Weekly until 3 Nov 2026". A null [summary] is a rule the app cannot describe in full.
 *
 * @param locale for weekday names and the UNTIL date.
 */
fun recurrenceText(summary: RecurrenceSummary?, locale: Locale): UiText {
    if (summary == null) return uiText(R.string.repeat_custom)
    val n = summary.interval
    val base = when (summary.frequency) {
        Frequency.DAILY -> if (n == 1) uiText(R.string.repeat_daily) else uiPlural(R.plurals.repeat_every_days, n)
        Frequency.WEEKLY -> if (n == 1) uiText(R.string.repeat_weekly) else uiPlural(R.plurals.repeat_every_weeks, n)
        Frequency.MONTHLY -> if (n == 1) uiText(R.string.repeat_monthly) else uiPlural(R.plurals.repeat_every_months, n)
        Frequency.YEARLY -> if (n == 1) uiText(R.string.repeat_yearly) else uiPlural(R.plurals.repeat_every_years, n)
        Frequency.NONE -> return uiText(R.string.repeat_custom)
    }
    val withDays = if (summary.weekdays.isEmpty()) {
        base
    } else {
        val days = summary.weekdays.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        uiText(R.string.repeat_on_days, base, days)
    }
    val count = summary.count
    val until = summary.until
    return when {
        count == 1 -> uiText(R.string.repeat_once, withDays)
        count != null -> uiPlural(R.plurals.repeat_times, count, withDays, count)
        until != null -> uiText(
            R.string.repeat_until,
            withDays,
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(until),
        )
        else -> withDays
    }
}
