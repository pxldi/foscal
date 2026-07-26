package app.foscal.notifications

import app.foscal.core.model.ScheduledReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Duration

class AlarmReminderSchedulerTest {

    private fun key(eventId: Long, startMillis: Long, minutes: Int) =
        AlarmReminderScheduler.alarmKey(eventId, startMillis, minutes)

    @Test
    fun `occurrences of one series get distinct alarm keys`() {
        val eventId = 42L
        val day = Duration.ofDays(1).toMillis()
        val start = 1_700_000_000_000L
        val keys = (0 until 30).map { key(eventId, start + it * day, 10) }

        assertEquals("every occurrence must map to its own alarm", 30, keys.toSet().size)
    }

    @Test
    fun `different reminder offsets on one occurrence get distinct keys`() {
        val start = 1_700_000_000_000L
        assertNotEquals(key(7L, start, 10), key(7L, start, 30))
    }

    @Test
    fun `different events at the same instant get distinct keys`() {
        val start = 1_700_000_000_000L
        assertNotEquals(key(7L, start, 10), key(8L, start, 10))
    }

    @Test
    fun `key is stable across calls`() {
        assertEquals(key(9L, 1_700_000_000_000L, 15), key(9L, 1_700_000_000_000L, 15))
    }

    @Test
    fun `trigger time is the occurrence start minus the offset`() {
        val start = 1_700_000_000_000L
        val reminder = ScheduledReminder(
            eventId = 1L,
            calendarId = 1L,
            title = "Standup",
            location = null,
            startMillis = start,
            minutesBefore = 15,
        )
        assertEquals(start - 15 * 60_000L, reminder.triggerAtMillis)
    }
}
