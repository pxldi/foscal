package app.foscal.ui.calendars

import app.foscal.testCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRowsTest {

    // Seen on a Pixel 9 on 2026-09-28: Outlook's "Birthdays" and "United States holidays" sat at
    // visible=0, sync_events=0 and still drew ticked in the drawer.
    @Test
    fun `a calendar that is not synced gets no row`() {
        val rows = calendarRows(
            all = listOf(testCalendar(id = 1), testCalendar(id = 2, visible = false, syncEnabled = false)),
            monthHidden = emptySet(),
            calendarReminders = emptyMap(),
        )
        assertEquals(listOf(1L), rows.map { it.calendar.id })
    }

    @Test
    fun `the tick is the provider's VISIBLE flag`() {
        val rows = calendarRows(
            all = listOf(testCalendar(id = 1), testCalendar(id = 2, visible = false)),
            monthHidden = emptySet(),
            calendarReminders = emptyMap(),
        )
        assertFalse(rows.single { it.calendar.id == 1L }.isHidden)
        assertTrue(rows.single { it.calendar.id == 2L }.isHidden)
    }

    @Test
    fun `month and reminder choices still come from Foscal's own settings`() {
        val row = calendarRows(
            all = listOf(testCalendar(id = 4)),
            monthHidden = setOf("4"),
            calendarReminders = mapOf(4L to null),
        ).single()
        assertTrue(row.isHiddenInMonth)
        assertFalse(row.usesGlobalReminder)
        assertEquals(null, row.reminderOverride)
    }
}
