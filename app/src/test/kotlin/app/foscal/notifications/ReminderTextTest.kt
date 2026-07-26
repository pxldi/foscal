package app.foscal.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTextTest {

    private val now = 1_700_000_000_000L
    private fun minutes(n: Long) = n * 60_000L

    @Test
    fun `lead keeps the second unit instead of truncating to one`() {
        assertEquals("1h 30m", formatLead(90))
        assertEquals("1d 1h", formatLead(1500))
        assertEquals("10m", formatLead(10))
        assertEquals("2h", formatLead(120))
        assertEquals("7d", formatLead(10080))
    }

    @Test
    fun `label is measured from now, not from the configured offset`() {
        // The alarm for a 10-minute reminder fired 11 days early. The notification must expose the
        // real distance to the event rather than restating the offset it was scheduled with.
        val start = now + minutes(11 * 24 * 60)
        assertEquals("In 11d", leadLabel(start, now))
    }

    @Test
    fun `label reports the delay when an alarm arrives late`() {
        assertEquals("5m ago", leadLabel(now - minutes(5), now))
    }

    @Test
    fun `label says now at the moment the event starts`() {
        assertEquals("Now", leadLabel(now, now))
    }

    @Test
    fun `label is absent when the occurrence start is unknown`() {
        assertNull(leadLabel(0L, now))
    }
}
