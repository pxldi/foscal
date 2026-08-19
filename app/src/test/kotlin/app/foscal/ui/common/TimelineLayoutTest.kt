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
    fun partlyOverlappingEvents_goSideBySide() {
        val positioned = layoutTimed(
            listOf(eventAt(9 * 60, 10 * 60), eventAt(9 * 60 + 30, 10 * 60 + 30)),
            60.dp,
            zone,
        )
        assertEquals(2, positioned.size)
        assertTrue("neither event contains the other", positioned.all { it.depth == 0 })
        assertEquals(setOf(0f, 0.5f), positioned.map { it.leftFraction }.toSet())
        assertTrue("each takes half the column", positioned.all { it.widthFraction == 0.5f })
    }

    /**
     * The one the week grid used to get wrong: a break booked inside a workday halved the column
     * for the whole workday, and "Arbeit" came out as "Ar / bei / t".
     */
    @Test
    fun aContainedEvent_isIndentedOnTopInsteadOfHalvingTheColumn() {
        val work = eventAt(8 * 60 + 15, 15 * 60)
        val lunch = eventAt(12 * 60, 12 * 60 + 45)

        val positioned = layoutTimed(listOf(work, lunch), 60.dp, zone)

        val outer = positioned.first { it.event.start == work.start }
        val inner = positioned.first { it.event.start == lunch.start }
        assertEquals(0, outer.depth)
        assertEquals(1, inner.depth)
        assertEquals("the container keeps the whole column", 1f, outer.widthFraction, 0.001f)
        assertTrue("the nested block is indented", inner.leftFraction > outer.leftFraction)
        assertEquals(
            "and still runs to the container's right edge",
            outer.leftFraction + outer.widthFraction,
            inner.leftFraction + inner.widthFraction,
            0.001f,
        )
        assertTrue("nested blocks are drawn last", positioned.last().depth == 1)
    }

    /** Nesting is recursive: a call inside a break inside a workday steps in twice. */
    @Test
    fun nestingGoesDeeperThanOneLevel() {
        val positioned = layoutTimed(
            listOf(
                eventAt(8 * 60, 17 * 60),
                eventAt(12 * 60, 13 * 60),
                eventAt(12 * 60 + 15, 12 * 60 + 30),
            ),
            60.dp,
            zone,
        )
        assertEquals(listOf(0, 1, 2), positioned.map { it.depth })
        val lefts = positioned.map { it.leftFraction }
        assertTrue("each level steps further in", lefts[0] < lefts[1] && lefts[1] < lefts[2])
    }

    /** Two events on exactly the same slot are peers, not one inside the other. */
    @Test
    fun identicalSpans_shareTheColumnRatherThanNest() {
        val positioned = layoutTimed(
            listOf(eventAt(9 * 60, 10 * 60), eventAt(9 * 60, 10 * 60)),
            60.dp,
            zone,
        )
        assertTrue(positioned.all { it.depth == 0 })
        assertEquals(setOf(0f, 0.5f), positioned.map { it.leftFraction }.toSet())
    }

    /**
     * A ten-minute event is drawn at the minimum height whatever its length says, so it reaches
     * past its own end and under the event that starts there. What is reported as visible is the
     * strip above that event's top edge — the only part its title can safely sit in.
     */
    @Test
    fun aBlockTooShortToDraw_reportsOnlyTheStripThatStaysInView() {
        val wake = eventAt(5 * 60 + 50, 6 * 60)
        val gym = eventAt(6 * 60, 7 * 60 + 15)

        val positioned = layoutTimed(listOf(wake, gym), 48.dp, zone)

        val short = positioned.first { it.event.start == wake.start }
        val long = positioned.first { it.event.start == gym.start }
        assertEquals("ten minutes of a 48dp hour", 8f, short.visibleDp.value, 0.01f)
        assertTrue("but it is still drawn tall enough to hold a word", short.heightDp > short.visibleDp)
        assertEquals("a block nothing covers is visible all the way down", long.heightDp, long.visibleDp)
    }

    /** Back-to-back events are not an overlap: the second gets the full width back. */
    @Test
    fun backToBackEvents_bothKeepTheFullColumn() {
        val positioned = layoutTimed(
            listOf(eventAt(9 * 60, 10 * 60), eventAt(10 * 60, 11 * 60)),
            60.dp,
            zone,
        )
        assertTrue(positioned.all { it.widthFraction == 1f && it.leftFraction == 0f })
    }
}
