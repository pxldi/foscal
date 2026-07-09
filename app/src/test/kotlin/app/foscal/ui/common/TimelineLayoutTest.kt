package app.foscal.ui.common

import androidx.compose.ui.unit.dp
import app.foscal.allDayEvent
import app.foscal.core.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineLayoutTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val date: LocalDate = LocalDate.of(2026, 7, 7)

    private fun eventAt(startMin: Int, endMin: Int): Event {
        val base = date.atStartOfDay(zone)
        return Event(
            id = 1,
            calendarId = 1,
            title = "e",
            location = null,
            description = null,
            start = base.plusMinutes(startMin.toLong()).toInstant(),
            end = base.plusMinutes(endMin.toLong()).toInstant(),
            allDay = false,
            timezone = "UTC",
            color = 0,
        )
    }

    /** Regression: an event starting after 23:45 used to crash layoutTimed via an empty coerce range. */
    @Test
    fun lateNightEvent_doesNotCrash() {
        val positioned = layoutTimed(listOf(eventAt(23 * 60 + 59, 24 * 60)), 60.dp, zone)
        assertEquals(1, positioned.size)
        assertTrue("height must stay positive", positioned[0].heightDp.value > 0f)
    }

    @Test
    fun computeAllDaySpans_collapsesAMultiDayEventIntoOneSpanningBar() {
        val trip = allDayEvent(1, startDay = date, days = 3)
        val days = (0..2).map { TimelineDay(date.plusDays(it.toLong()), listOf(trip)) }

        val spans = computeAllDaySpans(days)

        assertEquals(1, spans.size)
        assertEquals(0, spans[0].firstCol)
        assertEquals(2, spans[0].lastCol)
    }

    @Test
    fun computeAllDaySpans_keepsDailyRecurringInstancesSeparate() {
        // Same event id, but distinct instance start days -> one bar per day, not one wide bar.
        val mon = allDayEvent(1, startDay = date, days = 1)
        val tue = allDayEvent(1, startDay = date.plusDays(1), days = 1)
        val days = listOf(
            TimelineDay(date, listOf(mon)),
            TimelineDay(date.plusDays(1), listOf(tue)),
        )

        val spans = computeAllDaySpans(days)

        assertEquals(2, spans.size)
        assertTrue(spans.all { it.firstCol == it.lastCol })
    }

    @Test
    fun assignAllDayLanes_stacksOverlapsAndReusesLanesForGaps() {
        val e = allDayEvent(1, startDay = date, days = 1)
        val a = AllDaySpan(e, firstCol = 0, lastCol = 2)
        val b = AllDaySpan(e, firstCol = 1, lastCol = 3) // overlaps a
        val c = AllDaySpan(e, firstCol = 3, lastCol = 4) // clears a, overlaps b

        val lanes = assignAllDayLanes(listOf(a, b, c))

        assertEquals(2, lanes.size)
        assertEquals(listOf(a, c), lanes[0]) // c drops back into a's lane once a has ended
        assertEquals(listOf(b), lanes[1])
    }

    @Test
    fun overlappingEvents_splitIntoColumns() {
        val positioned = layoutTimed(
            listOf(eventAt(9 * 60, 10 * 60), eventAt(9 * 60 + 30, 10 * 60 + 30)),
            60.dp,
            zone,
        )
        assertEquals(2, positioned.size)
        assertEquals(2, positioned[0].columnCount)
        assertEquals(setOf(0, 1), positioned.map { it.column }.toSet())
    }
}
