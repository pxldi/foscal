package app.calendarium.core.model

import java.time.Instant

enum class Frequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * Editable form of an event, used when creating or updating via [CalendarRepository].
 *
 * For recurring events, [end] is used only to compute the per-instance duration; the
 * stored event uses DURATION + RRULE per the iCalendar spec (and Android's Calendar Provider
 * requires exactly that — DURATION is forbidden with DTEND and vice-versa).
 */
data class EventInput(
    val calendarId: Long,
    val title: String,
    val location: String?,
    val description: String?,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val timezone: String,
    val frequency: Frequency = Frequency.NONE,
    val reminderMinutesBefore: Int? = 15,
)
