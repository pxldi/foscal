package app.calendarium.core.model

import java.time.Instant

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
) {
    val durationMillis: Long
        get() = end.toEpochMilli() - start.toEpochMilli()
}
