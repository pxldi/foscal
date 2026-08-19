package app.foscal.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** What a drag or a tap on empty grid turns into, before any of it reaches the editor. */
class TimelineSelectionTest {

    private val day: LocalDate = LocalDate.of(2026, 8, 19)

    @Test
    fun `a minute rounds to the nearest ten rather than down to it`() {
        assertEquals(540, 544.roundToStep(10))
        assertEquals(550, 545.roundToStep(10))
        assertEquals(550, 546.roundToStep(10))
    }

    @Test
    fun `a tapped minute floors, so the block never starts above the finger`() {
        assertEquals(540, 549.floorToStep(10))
        assertEquals(550, 550.floorToStep(10))
        assertEquals(0, 9.floorToStep(10))
    }

    @Test
    fun `a tap lands on the hour, wherever in it the finger was`() {
        assertEquals(540, 540.floorToStep(60))
        assertEquals(540, 559.floorToStep(60))
        assertEquals(540, 599.floorToStep(60))
        assertEquals(600, 600.floorToStep(60))
    }

    @Test
    fun `a drag downwards is the range it covers`() {
        val span = TimeSelection(day, startMinute = 540, endMinute = 610).span(60)
        assertEquals(540, span.first)
        assertEquals(610, span.second)
    }

    @Test
    fun `a drag upwards means the same range as the same drag downwards`() {
        val up = TimeSelection(day, startMinute = 610, endMinute = 540).span(60)
        val down = TimeSelection(day, startMinute = 540, endMinute = 610).span(60)
        assertEquals(down, up)
    }

    @Test
    fun `a long press that never moved is the default length, not a zero-length event`() {
        val span = TimeSelection(day, startMinute = 540, endMinute = 540).span(45)
        assertEquals(540, span.first)
        assertEquals(585, span.second)
    }

    @Test
    fun `nothing runs past midnight`() {
        val span = TimeSelection(day, startMinute = 1430, endMinute = 1430).span(60)
        assertEquals(24 * 60, span.second)
        assertEquals(24 * 60, NewEventPlacement(day, 1430).span(60).second)
    }

    @Test
    fun `a parked block is exactly the default length`() {
        val span = NewEventPlacement(day, startMinute = 600).span(60)
        assertEquals(600, span.first)
        assertEquals(660, span.second)
    }
}
