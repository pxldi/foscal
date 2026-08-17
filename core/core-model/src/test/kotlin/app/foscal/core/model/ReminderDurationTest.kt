package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderDurationTest {

    @Test
    fun `converts each unit to minutes`() {
        assertEquals(45, ReminderDuration.toMinutes(45, ReminderUnit.MINUTES))
        assertEquals(120, ReminderDuration.toMinutes(2, ReminderUnit.HOURS))
        assertEquals(4320, ReminderDuration.toMinutes(3, ReminderUnit.DAYS))
        assertEquals(20160, ReminderDuration.toMinutes(2, ReminderUnit.WEEKS))
    }

    @Test
    fun `zero is a valid offset meaning at start`() {
        assertEquals(0, ReminderDuration.toMinutes(0, ReminderUnit.HOURS))
    }

    @Test
    fun `rejects negative amounts`() {
        assertNull(ReminderDuration.toMinutes(-1, ReminderUnit.MINUTES))
    }

    @Test
    fun `accepts the maximum exactly`() {
        assertEquals(ReminderDuration.MAX_MINUTES, ReminderDuration.toMinutes(4, ReminderUnit.WEEKS))
        assertEquals(ReminderDuration.MAX_MINUTES, ReminderDuration.toMinutes(28, ReminderUnit.DAYS))
    }

    @Test
    fun `rejects anything past the maximum`() {
        assertNull(ReminderDuration.toMinutes(5, ReminderUnit.WEEKS))
        assertNull(ReminderDuration.toMinutes(29, ReminderUnit.DAYS))
    }

    /**
     * `40320 * 10080` wraps to a *negative* Int, which a naive `value * unit.minutes > MAX` check
     * would happily accept and store as a reminder in the past.
     */
    @Test
    fun `rejects an amount that would overflow Int`() {
        assertNull(ReminderDuration.toMinutes(Int.MAX_VALUE, ReminderUnit.WEEKS))
        assertNull(ReminderDuration.toMinutes(500_000, ReminderUnit.HOURS))
    }

    @Test
    fun `splits to the coarsest exact unit`() {
        assertEquals(30 to ReminderUnit.MINUTES, ReminderDuration.split(30))
        assertEquals(2 to ReminderUnit.HOURS, ReminderDuration.split(120))
        assertEquals(1 to ReminderUnit.DAYS, ReminderDuration.split(1440))
        assertEquals(1 to ReminderUnit.WEEKS, ReminderDuration.split(10080))
        assertEquals(2 to ReminderUnit.WEEKS, ReminderDuration.split(20160))
    }

    @Test
    fun `splits an inexact amount to the finest unit`() {
        assertEquals(90 to ReminderUnit.MINUTES, ReminderDuration.split(90))
        assertEquals(25 to ReminderUnit.HOURS, ReminderDuration.split(1500))
    }

    /** Every offset the picker can produce must come back out of the picker unchanged. */
    @Test
    fun `split round-trips through toMinutes`() {
        for (minutes in listOf(1, 5, 15, 30, 45, 60, 90, 120, 1440, 2880, 10080, 20160, 40320)) {
            val (value, unit) = ReminderDuration.split(minutes)
            assertEquals(
                "round trip failed for $minutes",
                minutes,
                ReminderDuration.toMinutes(value, unit),
            )
        }
    }

    @Test
    fun `labels use the coarsest unit and pluralize`() {
        assertEquals("At start", ReminderDuration.label(0))
        assertEquals("5 min", ReminderDuration.label(5))
        assertEquals("90 min", ReminderDuration.label(90))
        assertEquals("1 hour", ReminderDuration.label(60))
        assertEquals("2 hours", ReminderDuration.label(120))
        assertEquals("1 day", ReminderDuration.label(1440))
        assertEquals("3 days", ReminderDuration.label(4320))
        assertEquals("1 week", ReminderDuration.label(10080))
        assertEquals("2 weeks", ReminderDuration.label(20160))
    }

    /** `Reminders.MINUTES_DEFAULT` is -1; it must never surface as "-1 min". */
    @Test
    fun `labels a negative sentinel as at start`() {
        assertEquals("At start", ReminderDuration.label(-1))
    }
}
