package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarReminderDefaultsTest {

    @Test
    fun `round-trips offsets and explicit None`() {
        val defaults = mapOf(1L to 30, 2L to null, 3L to 0, 4L to 10080)
        assertEquals(defaults, CalendarReminderDefaults.decode(CalendarReminderDefaults.encode(defaults)))
    }

    @Test
    fun `encodes None as the sentinel and zero as zero`() {
        assertEquals(setOf("7:-1"), CalendarReminderDefaults.encode(mapOf(7L to null)))
        assertEquals(setOf("7:0"), CalendarReminderDefaults.encode(mapOf(7L to 0)))
    }

    /**
     * "At start" and "None" are different answers, and 0 vs -1 is the only thing separating them in
     * storage. Collapsing them would silently add an alarm to a calendar the user turned off.
     */
    @Test
    fun `distinguishes at-start from None`() {
        val decoded = CalendarReminderDefaults.decode(setOf("1:0", "2:-1"))
        assertEquals(0, decoded[1L])
        assertNull(decoded[2L])
        assertTrue(decoded.containsKey(2L))
    }

    @Test
    fun `drops unparseable entries rather than failing the read`() {
        val decoded = CalendarReminderDefaults.decode(
            setOf("1:30", "", ":15", "nope:15", "2:abc", "3", "4:45"),
        )
        assertEquals(mapOf(1L to 30, 4L to 45), decoded)
    }

    @Test
    fun `decodes an empty set to an empty map`() {
        assertTrue(CalendarReminderDefaults.decode(emptySet()).isEmpty())
    }

    @Test
    fun `resolve falls back to the global default when a calendar has no override`() {
        assertEquals(15, CalendarReminderDefaults.resolve(1L, emptyMap(), global = 15))
    }

    @Test
    fun `resolve prefers the calendar override`() {
        assertEquals(30, CalendarReminderDefaults.resolve(1L, mapOf(1L to 30), global = 15))
    }

    /**
     * The whole point of the map: an override of "None" has to beat a non-null global, which a
     * `perCalendar[id] ?: global` lookup would get exactly backwards.
     */
    @Test
    fun `resolve honours an explicit None over a global default`() {
        assertNull(CalendarReminderDefaults.resolve(1L, mapOf(1L to null), global = 15))
    }

    @Test
    fun `resolve uses the global default when no calendar is selected`() {
        assertEquals(15, CalendarReminderDefaults.resolve(null, mapOf(1L to 30), global = 15))
    }

    @Test
    fun `resolve returns null when neither has a value`() {
        assertNull(CalendarReminderDefaults.resolve(1L, emptyMap(), global = null))
    }
}
