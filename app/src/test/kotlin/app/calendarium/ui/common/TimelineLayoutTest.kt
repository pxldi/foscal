package app.calendarium.ui.common

import androidx.compose.ui.unit.dp
import app.calendarium.core.model.Event
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
