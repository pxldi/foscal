package app.foscal.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddParserTest {

    // A fixed "today" so date math is deterministic: Wednesday 2026-07-08.
    private val today = LocalDate.of(2026, 7, 8)

    @Test
    fun `bare text becomes the title with no date or time`() {
        val r = QuickAddParser.parse("Lunch with Sam", today)
        assertEquals("Lunch with Sam", r.title)
        assertNull(r.date)
        assertNull(r.time)
        assertFalse(r.allDay)
    }

    @Test
    fun `blank input falls back to untitled`() {
        val r = QuickAddParser.parse("   ", today)
        assertEquals("(Untitled)", r.title)
    }

    @Test
    fun `parses today`() {
        val r = QuickAddParser.parse("Standup today", today)
        assertEquals(today, r.date)
        assertEquals("Standup", r.title)
    }

    @Test
    fun `parses tomorrow and tmrw`() {
        assertEquals(today.plusDays(1), QuickAddParser.parse("Call tomorrow", today).date)
        assertEquals(today.plusDays(1), QuickAddParser.parse("Call tmrw", today).date)
    }

    @Test
    fun `bare weekday resolves on or after today`() {
        // Today is Wednesday; "monday" -> next Monday (5 days forward).
        val r = QuickAddParser.parse("Gym monday", today)
        assertEquals(LocalDate.of(2026, 7, 13), r.date)
        assertEquals("Gym", r.title)
    }

    @Test
    fun `bare weekday matching today resolves to today`() {
        // Today is Wednesday; "wednesday" -> today.
        val r = QuickAddParser.parse("Pickup wed", today)
        assertEquals(today, r.date)
    }

    @Test
    fun `next weekday skips today`() {
        // "next wed" from Wednesday 2026-07-08 -> 2026-07-15.
        val r = QuickAddParser.parse("Review next wed", today)
        assertEquals(LocalDate.of(2026, 7, 15), r.date)
    }

    @Test
    fun `does not read a weekday out of a longer word`() {
        // "monthly" must not be read as "mon".
        val r = QuickAddParser.parse("Monthly review", today)
        assertEquals("Monthly review", r.title)
        assertNull(r.date)
    }

    @Test
    fun `parses 12h time with colon and am`() {
        val r = QuickAddParser.parse("Flight at 7:30am", today)
        assertEquals(LocalTime.of(7, 30), r.time)
        assertEquals("Flight at", r.title)
    }

    @Test
    fun `parses 12h time without colon and pm`() {
        val r = QuickAddParser.parse("Dinner 8pm", today)
        assertEquals(LocalTime.of(20, 0), r.time)
    }

    @Test
    fun `parses 12h time with variant suffix`() {
        assertEquals(LocalTime.of(15, 0), QuickAddParser.parse("Meet 3 p.m.", today).time)
        assertEquals(LocalTime.of(0, 0), QuickAddParser.parse("Meet 12am", today).time)
        assertEquals(LocalTime.of(12, 0), QuickAddParser.parse("Meet 12pm", today).time)
    }

    @Test
    fun `parses 24h time`() {
        val r = QuickAddParser.parse("Train 13:45", today)
        assertEquals(LocalTime.of(13, 45), r.time)
    }

    @Test
    fun `noon and midnight`() {
        assertEquals(LocalTime.of(12, 0), QuickAddParser.parse("Lunch noon", today).time)
        assertEquals(LocalTime.of(0, 0), QuickAddParser.parse("Walk midnight", today).time)
    }

    @Test
    fun `bare numbers without am-pm or colon stay in the title`() {
        val r = QuickAddParser.parse("Buy 2 tickets", today)
        assertEquals("Buy 2 tickets", r.title)
        assertNull(r.time)
    }

    @Test
    fun `all-day marker sets allDay and strips the time`() {
        val r = QuickAddParser.parse("Conference all day", today)
        assertTrue(r.allDay)
        assertEquals("Conference", r.title)
        assertNull(r.time)
    }

    @Test
    fun `all-day hyphen form also matches`() {
        val r = QuickAddParser.parse("PTO all-day tomorrow", today)
        assertTrue(r.allDay)
        assertEquals(today.plusDays(1), r.date)
        assertEquals("PTO", r.title)
    }

    @Test
    fun `combined date and time`() {
        val r = QuickAddParser.parse("Dentist friday 9:30am", today)
        // Today Wed 2026-07-08 -> friday 2026-07-10.
        assertEquals(LocalDate.of(2026, 7, 10), r.date)
        assertEquals(LocalTime.of(9, 30), r.time)
        assertEquals("Dentist", r.title)
    }
}
