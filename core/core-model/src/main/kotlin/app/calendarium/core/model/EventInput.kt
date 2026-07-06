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
    /**
     * The verbatim RRULE to persist. When non-null it is written as-is, preserving details we
     * don't model in [frequency] (BYDAY, INTERVAL, UNTIL, …) for events synced from CalDAV.
     * When null, a simple rule is derived from [frequency].
     */
    val rrule: String? = null,
    val reminderMinutesBefore: Int? = 15,
)
