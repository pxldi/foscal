package app.foscal.ui.common

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeToPageTest {

    private val slop = 12f

    @Test
    fun `a drag is nothing until it has travelled the slop`() {
        assertNull(swipeAxis(Offset.Zero, slop))
        assertNull(swipeAxis(Offset(8f, 8f), slop))
    }

    @Test
    fun `the axis is whichever way the drag has actually got furthest`() {
        assertEquals(SwipeAxis.Horizontal, swipeAxis(Offset(30f, 10f), slop))
        assertEquals(SwipeAxis.Horizontal, swipeAxis(Offset(-30f, 10f), slop))
        assertEquals(SwipeAxis.Vertical, swipeAxis(Offset(10f, 30f), slop))
        assertEquals(SwipeAxis.Vertical, swipeAxis(Offset(10f, -30f), slop))
    }

    @Test
    fun `a dead-diagonal drag pages rather than scrolls`() {
        // The tie has to go somewhere, and it goes to the gesture that has to fight for it: a
        // scroller underneath takes everything it is offered, so leaving ties to it is what made
        // a swipe meant for the next week scroll the day instead.
        assertEquals(SwipeAxis.Horizontal, swipeAxis(Offset(20f, 20f), slop))
        assertEquals(SwipeAxis.Horizontal, swipeAxis(Offset(-20f, 20f), slop))
    }

    @Test
    fun `the decision is taken on total displacement, not on the last wobble`() {
        // A finger that starts down-left and then travels right is a rightward swipe.
        val total = Offset(0f, 6f) + Offset(40f, -2f)
        assertEquals(SwipeAxis.Horizontal, swipeAxis(total, slop))
    }

    @Test
    fun `swipe direction still needs the full threshold`() {
        val threshold = 56f
        assertNull(swipeDirection(-55f, threshold))
        assertEquals(SwipeDirection.Next, swipeDirection(-56f, threshold))
        assertEquals(SwipeDirection.Previous, swipeDirection(56f, threshold))
    }
}
