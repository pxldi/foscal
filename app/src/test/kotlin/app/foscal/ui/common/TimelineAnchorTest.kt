package app.foscal.ui.common

import app.foscal.core.model.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimelineAnchorTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 7, 26)

    private fun event(date: LocalDate, hour: Int, durationMin: Long = 60): Event {
        val start = date.atStartOfDay(zone).plusHours(hour.toLong())
        return Event(
            id = 1,
            calendarId = 1,
            title = "e",
            location = null,
            description = null,
            start = start.toInstant(),
            end = start.plusMinutes(durationMin).toInstant(),
            allDay = false,
            timezone = "UTC",
            color = 0,
        )
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant()

    @Test
    fun `week containing today anchors on the current hour`() {
        val days = listOf(TimelineDay(today, listOf(event(today, 17))))
        assertEquals(15, anchorHour(days, today, at(today, 16, 44), zone))
    }

    /** The old fixed 06:00 anchor hid the rest of the day whenever the app was opened late. */
    @Test
    fun `late in the day the anchor follows now rather than the morning`() {
        val days = listOf(TimelineDay(today, listOf(event(today, 9))))
        assertEquals(21, anchorHour(days, today, at(today, 22, 10), zone))
    }

    @Test
    fun `other weeks anchor on their first timed event`() {
        val other = today.plusWeeks(2)
        val days = listOf(
            TimelineDay(other, listOf(event(other, 14))),
            TimelineDay(other.plusDays(1), listOf(event(other.plusDays(1), 9))),
        )
        assertEquals(8, anchorHour(days, today, at(today, 16, 0), zone))
    }

    @Test
    fun `an empty week falls back to the default morning hour`() {
        val other = today.plusWeeks(2)
        val days = listOf(TimelineDay(other, emptyList()))
        assertEquals(7, anchorHour(days, today, at(today, 16, 0), zone))
    }

    /** A spill-over event started on an earlier, off-screen day must not drag the anchor back. */
    @Test
    fun `events starting before the shown days are ignored`() {
        val other = today.plusWeeks(2)
        val spillOver = event(other.minusDays(1), 3, durationMin = 36 * 60)
        val days = listOf(TimelineDay(other, listOf(spillOver, event(other, 11))))
        assertEquals(10, anchorHour(days, today, at(today, 16, 0), zone))
    }

    @Test
    fun `anchor never goes negative just after midnight`() {
        val days = listOf(TimelineDay(today, emptyList()))
        assertEquals(0, anchorHour(days, today, at(today, 0, 20), zone))
    }
}
