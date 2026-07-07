package app.calendarium.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSpannedDaysTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun event(start: String, end: String, allDay: Boolean): Event =
        Event(
            id = 1,
            calendarId = 1,
            title = "t",
            location = null,
            description = null,
            start = Instant.parse(start),
            end = Instant.parse(end),
            allDay = allDay,
            timezone = if (allDay) "UTC" else "Europe/Berlin",
            color = 0,
        )

    @Test
    fun `single-day all-day event spans exactly its start day`() {
        // Provider stores END as exclusive UTC midnight of the next day.
        val e = event("2026-07-06T00:00:00Z", "2026-07-07T00:00:00Z", allDay = true)
        assertEquals(listOf(LocalDate.of(2026, 7, 6)), e.spannedDays(berlin))
        assertTrue(e.spansDay(LocalDate.of(2026, 7, 6), berlin))
        assertFalse(e.spansDay(LocalDate.of(2026, 7, 7), berlin))
    }

    @Test
    fun `three-day all-day event spans start through end-minus-one`() {
        val e = event("2026-07-06T00:00:00Z", "2026-07-09T00:00:00Z", allDay = true)
        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 6),
                LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 8),
            ),
            e.spannedDays(berlin),
        )
        assertTrue(e.spansDay(LocalDate.of(2026, 7, 8), berlin))
        assertFalse(e.spansDay(LocalDate.of(2026, 7, 9), berlin))
    }

    @Test
    fun `single-day timed event spans exactly its start day`() {
        val e = event("2026-07-06T10:00:00+02:00", "2026-07-06T11:00:00+02:00", allDay = false)
        assertEquals(listOf(LocalDate.of(2026, 7, 6)), e.spannedDays(berlin))
    }

    @Test
    fun `overnight timed event spans both local days it touches`() {
        // Jul 6 22:00 Berlin -> Jul 7 03:00 Berlin
        val e = event("2026-07-06T20:00:00Z", "2026-07-07T01:00:00Z", allDay = false)
        assertEquals(
            listOf(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7)),
            e.spannedDays(berlin),
        )
    }

    @Test
    fun `timed event ending exactly at midnight does not spill into the next day`() {
        // Jul 6 16:00 Berlin -> Jul 7 00:00 Berlin is half-open, so it covers only Jul 6.
        val e = event("2026-07-06T14:00:00Z", "2026-07-06T22:00:00Z", allDay = false)
        assertEquals(listOf(LocalDate.of(2026, 7, 6)), e.spannedDays(berlin))
        assertFalse(e.spansDay(LocalDate.of(2026, 7, 7), berlin))
    }

    @Test
    fun `all-day event ignores the passed zone and uses UTC`() {
        // If it used Berlin, the start would shift a day for the UTC-midnight instant.
        val e = event("2026-07-06T00:00:00Z", "2026-07-07T00:00:00Z", allDay = true)
        assertEquals(LocalDate.of(2026, 7, 6), e.startLocalDate(berlin))
    }
}
