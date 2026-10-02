package app.foscal.ui.common

import app.foscal.allDayEvent
import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.testCalendar
import app.foscal.timedEvent
import app.foscal.ui.editor.RecurrenceScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class MoveEventTest {

    private val zone = ZoneId.of("Europe/Vienna")
    private val day = LocalDate.of(2026, 10, 2)

    @Test
    fun `a timed event keeps its length on its new day and time`() {
        val event = timedEvent(1, at(day, 9, zone = zone), at(day, 10, 30, zone = zone))
        val (start, end) = movedTimes(event, day.plusDays(5), LocalTime.of(14, 0), zone)
        assertEquals(at(day.plusDays(5), 14, zone = zone), start)
        assertEquals(at(day.plusDays(5), 15, 30, zone = zone), end)
    }

    // All-day rows are UTC midnight to UTC midnight. Going through the device zone would put the
    // event on the day before anywhere west of Greenwich.
    @Test
    fun `an all-day event moves by whole UTC days and ignores the time`() {
        val event = allDayEvent(1, day, days = 2)
        val (start, end) = movedTimes(event, day.plusDays(3), LocalTime.of(14, 0), ZoneId.of("America/Los_Angeles"))
        assertEquals(day.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant(), start)
        assertEquals(day.plusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant(), end)
    }

    @Test
    fun `an all-day move stays all-day and is written in UTC`() = runTest {
        val event = allDayEvent(1, day)
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()), events = listOf(event))
        val (start, end) = movedTimes(event, day.plusDays(1), LocalTime.MIDNIGHT, zone)

        assertTrue(repo.moveEvent(event, start.toEpochMilli(), end.toEpochMilli(), RecurrenceScope.SINGLE, zone))

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
        assertTrue(repo.lastWritten!!.allDay)
        assertEquals("UTC", repo.lastWritten!!.timezone)
        assertEquals(start, repo.lastWritten!!.start)
    }

    @Test
    fun `moving one occurrence writes an exception without the rule`() = runTest {
        val event = timedEvent(1, at(day, 9, zone = zone), at(day, 10, zone = zone))
            .copy(rrule = "FREQ=WEEKLY")
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()), events = listOf(event))
        val newStart = at(day, 11, zone = zone)

        repo.moveEvent(
            event,
            newStart.toEpochMilli(),
            newStart.plusSeconds(3600).toEpochMilli(),
            RecurrenceScope.SINGLE,
            zone,
        )

        assertEquals(FakeCalendarRepository.Op.UPDATE_INSTANCE, repo.lastOp)
        assertEquals(event.start.toEpochMilli(), repo.instanceUpdates.single().second)
        assertEquals(null, repo.lastWritten?.rrule)
    }

    @Test
    fun `moving the whole series shifts the master by the occurrence's delta`() = runTest {
        // The fake returns this row for any lookup of id 1, so it stands in for the master too.
        val event = timedEvent(1, at(day, 9, zone = zone), at(day, 10, zone = zone))
            .copy(rrule = "FREQ=DAILY")
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()), events = listOf(event))
        val newStart: Instant = at(day, 9, 30, zone = zone)

        repo.moveEvent(
            event,
            newStart.toEpochMilli(),
            newStart.plusSeconds(3600).toEpochMilli(),
            RecurrenceScope.ALL_EVENTS,
            zone,
        )

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
        assertEquals(newStart, repo.lastWritten?.start)
        assertEquals("FREQ=DAILY", repo.lastWritten?.rrule)
    }
}
