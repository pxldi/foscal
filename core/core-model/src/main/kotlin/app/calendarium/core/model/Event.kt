package app.calendarium.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class Event(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    val description: String?,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val timezone: String?,
    /** ARGB color resolved from the event's display color, falling back to its calendar color. */
    val color: Int,
    /** Non-null when this event is part of a recurring series (its RRULE string). */
    val rrule: String? = null,
) {
    val durationMillis: Long
        get() = end.toEpochMilli() - start.toEpochMilli()

    val isRecurring: Boolean get() = !rrule.isNullOrBlank()

    /**
     * The calendar day this event starts on. All-day events are stored at UTC midnight by the
     * Calendar Provider, so they must be read back in UTC — interpreting them in the device zone
     * shifts them a day earlier for anyone west of UTC. Timed events use the device zone.
     */
    fun startLocalDate(zone: ZoneId): LocalDate =
        if (allDay) start.atZone(ZoneOffset.UTC).toLocalDate()
        else start.atZone(zone).toLocalDate()
}
