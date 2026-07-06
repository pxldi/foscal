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

    /**
     * The calendar day this event ends on, in the raw provider sense: for timed events this is the
     * actual end day (inclusive); for all-day events the provider stores END as exclusive UTC
     * midnight of the day *after* the last covered day. See [spannedDays] for the inclusive range.
     */
    fun endLocalDate(zone: ZoneId): LocalDate =
        if (allDay) end.atZone(ZoneOffset.UTC).toLocalDate()
        else end.atZone(zone).toLocalDate()

    /**
     * Inclusive last day the event appears on. All-day events subtract one day because the
     * provider's END is exclusive (an all-day event Mon→Tue covers only Monday).
     */
    fun lastLocalDate(zone: ZoneId): LocalDate {
        val raw = endLocalDate(zone)
        return if (allDay) raw.minusDays(1) else raw
    }

    /** All calendar days this event covers, inclusive. Multi-day events span every day. */
    fun spannedDays(zone: ZoneId): List<LocalDate> {
        val first = startLocalDate(zone)
        val last = lastLocalDate(zone).coerceAtLeast(first)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
    }

    /** Whether this event covers [date] on any of its spanned days. */
    fun spansDay(date: LocalDate, zone: ZoneId): Boolean {
        val first = startLocalDate(zone)
        val last = lastLocalDate(zone).coerceAtLeast(first)
        return date in first..last
    }
}
