package app.foscal.notifications

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Inside this, a reminder says how long you have. Outside it, it says when the thing is. */
private const val RelativeWindowMinutes = 60L

/** Beyond a week out, the weekday alone stops being enough to place a date. */
private const val NamedWeekdayDays = 7L

/**
 * When the event is, in one phrase.
 *
 * Reminders used to print the lead time, the date and the clock time side by side — "In 15m · Tue,
 * Aug 19 · 11:30" — which is the same fact three times and reads as none of them. Only one of those
 * is ever the useful one, and which one depends entirely on how far away the event is.
 *
 * Close to the event, the lead time is what you act on: "In 15 min" answers whether to get up now,
 * and the notification's own timestamp already carries the clock time beside it. Far from it, the
 * lead time is the useless one — "in 2 weeks" tells you nothing you can put in a week — so it gives
 * the day instead, named while a weekday still identifies it and dated once it no longer does.
 *
 * [startMillis] is when the event begins as the reader understands it: local midnight for an
 * all-day event, not the UTC midnight the provider stores. Measured against [nowMillis] rather
 * than the reminder's configured offset, so an alarm held back by Doze admits how late it is
 * instead of insisting it is fifteen minutes early.
 */
internal fun reminderWhen(
    startMillis: Long,
    nowMillis: Long,
    allDay: Boolean,
    use24Hour: Boolean,
    zone: ZoneId,
    locale: Locale,
): String? {
    if (startMillis <= 0L) return null
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val minutes = Math.round((startMillis - nowMillis) / 60_000.0)

    if (!allDay && minutes > -RelativeWindowMinutes && minutes < RelativeWindowMinutes) {
        return when {
            minutes > 0L -> "In $minutes min"
            minutes == 0L -> "Now"
            else -> "${-minutes} min ago"
        }
    }

    val day = dayLabel(start.toLocalDate(), now.toLocalDate(), locale)
    if (allDay) return day
    val time = start.format(
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale),
    )
    return "$day at $time"
}

private fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale): String {
    val away = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays()
    return when {
        away == 0L -> "Today"
        away == 1L -> "Tomorrow"
        away == -1L -> "Yesterday"
        away in 2L..NamedWeekdayDays ->
            date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    }
}
