package app.foscal.notifications

import app.foscal.R
import app.foscal.ui.util.UiText
import app.foscal.ui.util.uiPlural
import app.foscal.ui.util.uiText
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
 *
 * Returned as [UiText] so the choice of phrase can be tested on the JVM; the receiver resolves it.
 * [datePattern] is the pattern for a date beyond a week out, passed in because the receiver asks
 * the platform for the locale's own order of weekday, day and month: "Tue, Sep 1" in English,
 * "Di., 1. Sept." in German.
 */
internal fun reminderWhen(
    startMillis: Long,
    nowMillis: Long,
    allDay: Boolean,
    use24Hour: Boolean,
    zone: ZoneId,
    locale: Locale,
    datePattern: String,
): UiText? {
    if (startMillis <= 0L) return null
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val minutes = Math.round((startMillis - nowMillis) / 60_000.0)

    if (!allDay && minutes > -RelativeWindowMinutes && minutes < RelativeWindowMinutes) {
        return when {
            minutes > 0L -> uiPlural(R.plurals.notification_in_minutes, minutes.toInt())
            minutes == 0L -> uiText(R.string.notification_now)
            else -> uiPlural(R.plurals.notification_minutes_ago, (-minutes).toInt())
        }
    }

    val day = dayLabel(start.toLocalDate(), now.toLocalDate(), locale, datePattern)
    if (allDay) return day
    val time = start.format(
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale),
    )
    return uiText(R.string.notification_day_at_time, day, time)
}

private fun dayLabel(
    date: LocalDate,
    today: LocalDate,
    locale: Locale,
    datePattern: String,
): UiText {
    val away = Duration.between(today.atStartOfDay(), date.atStartOfDay()).toDays()
    return when {
        away == 0L -> uiText(R.string.notification_today)
        away == 1L -> uiText(R.string.notification_tomorrow)
        away == -1L -> uiText(R.string.notification_yesterday)
        away in 2L..NamedWeekdayDays ->
            UiText.Raw(date.format(DateTimeFormatter.ofPattern("EEEE", locale)))
        else -> UiText.Raw(date.format(DateTimeFormatter.ofPattern(datePattern, locale)))
    }
}
