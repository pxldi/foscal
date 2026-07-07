package app.calendarium

import app.calendarium.core.model.Calendar
import app.calendarium.core.model.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared builders so view-model tests don't each re-declare the same boilerplate. */

fun testCalendar(
    id: Long = 1,
    visible: Boolean = true,
): Calendar = Calendar(
    id = id,
    displayName = "Cal $id",
    accountName = "Calendarium",
    accountType = "LOCAL",
    ownerName = null,
    color = 0xFF1976D2.toInt(),
    visible = visible,
    syncEnabled = true,
)

fun timedEvent(
    id: Long,
    start: Instant,
    end: Instant,
    calendarId: Long = 1,
    title: String = "Event $id",
    location: String? = null,
    description: String? = null,
): Event = Event(
    id = id,
    calendarId = calendarId,
    title = title,
    location = location,
    description = description,
    start = start,
    end = end,
    allDay = false,
    timezone = "UTC",
    color = 0xFF1976D2.toInt(),
)

/**
 * An all-day event covering [days] calendar days starting on [startDay]. Mirrors the provider's
 * storage: start at UTC midnight, end at exclusive UTC midnight after the last covered day.
 */
fun allDayEvent(
    id: Long,
    startDay: LocalDate,
    days: Long = 1,
    calendarId: Long = 1,
    title: String = "AllDay $id",
): Event = Event(
    id = id,
    calendarId = calendarId,
    title = title,
    location = null,
    description = null,
    start = startDay.atStartOfDay(ZoneOffset.UTC).toInstant(),
    end = startDay.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant(),
    allDay = true,
    timezone = "UTC",
    color = 0xFF1976D2.toInt(),
)

/** Instant at [time] on [date] in the given zone. */
fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = ZoneId.systemDefault()): Instant =
    date.atTime(hour, minute).atZone(zone).toInstant()
