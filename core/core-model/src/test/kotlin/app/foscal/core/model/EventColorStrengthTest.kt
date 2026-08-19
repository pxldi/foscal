package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventColorStrengthTest {

    @Test
    fun `the default leaves the colour alone, so nothing changes for anyone who never opens this`() {
        assertEquals(EventColorStrength.FULL, EventColorStrength.Default)
        assertEquals(0f, EventColorStrength.Default.wash, 0f)
    }

    @Test
    fun `every step is softer than the one before it`() {
        val washes = EventColorStrength.entries.map { it.wash }
        assertEquals(washes.sortedDescending(), washes)
    }

    @Test
    fun `no step washes the fill all the way into the background`() {
        assertTrue(EventColorStrength.entries.all { it.wash < 0.9f })
    }

    @Test
    fun `an unknown or missing stored key falls back rather than throwing`() {
        assertEquals(EventColorStrength.Default, EventColorStrength.fromKey(null))
        assertEquals(EventColorStrength.Default, EventColorStrength.fromKey("mauve"))
        assertEquals(EventColorStrength.SOFT, EventColorStrength.fromKey("soft"))
    }
}
