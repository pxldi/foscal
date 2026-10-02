package app.foscal.ui.util

import app.foscal.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderLabelsTest {

    @Test
    fun `labels use the coarsest unit`() {
        assertEquals(uiText(R.string.reminder_at_start), reminderLabel(0))
        assertEquals(uiText(R.string.reminder_minutes_short, 5), reminderLabel(5))
        assertEquals(uiText(R.string.reminder_minutes_short, 90), reminderLabel(90))
        assertEquals(uiPlural(R.plurals.reminder_hours, 1), reminderLabel(60))
        assertEquals(uiPlural(R.plurals.reminder_hours, 2), reminderLabel(120))
        assertEquals(uiPlural(R.plurals.reminder_days, 3), reminderLabel(4320))
        assertEquals(uiPlural(R.plurals.reminder_weeks, 2), reminderLabel(20160))
    }

    /** `Reminders.MINUTES_DEFAULT` is -1; it must never surface as "-1 min". */
    @Test
    fun `labels a negative sentinel as at start`() {
        assertEquals(uiText(R.string.reminder_at_start), reminderLabel(-1))
    }
}
