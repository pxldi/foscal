package app.foscal.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetRestingFractionTest {

    private val screen = 2400f

    @Test
    fun `an unmeasured sheet stays hidden`() {
        assertEquals(1f, sheetRestingFraction(sheetPx = 0f, screenPx = screen))
    }

    @Test
    fun `a short sheet opens whole`() {
        assertEquals(0f, sheetRestingFraction(sheetPx = 900f, screenPx = screen))
    }

    /**
     * The view sheet with four calendars on a 1080x2400 phone with 3-button navigation: just over
     * half the screen. Stopping at the halfway line left Settings half under the navigation bar.
     */
    @Test
    fun `a sheet just over half the screen opens whole`() {
        assertEquals(0f, sheetRestingFraction(sheetPx = 1325f, screenPx = screen))
    }

    @Test
    fun `a tall sheet stops at the halfway line`() {
        val sheet = 2200f
        val resting = sheetRestingFraction(sheetPx = sheet, screenPx = screen)
        assertEquals(screen / 2, sheet * (1f - resting), 0.5f)
    }
}
