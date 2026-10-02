package app.foscal.ui.week

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanTitleTest {

    @Test
    fun `this year's spans carry no year`() {
        assertFalse(spanShowsYear(LocalDate.of(2026, 9, 21), 7, 2026))
        assertFalse(spanShowsYear(LocalDate.of(2026, 9, 23), 1, 2026))
    }

    @Test
    fun `another year's spans name it`() {
        assertTrue(spanShowsYear(LocalDate.of(2027, 9, 20), 7, 2026))
        assertTrue(spanShowsYear(LocalDate.of(2027, 9, 23), 1, 2026))
    }

    @Test
    fun `a span that runs into next year names it`() {
        assertTrue(spanShowsYear(LocalDate.of(2026, 12, 28), 7, 2026))
        assertFalse(spanShowsYear(LocalDate.of(2026, 12, 25), 7, 2026))
    }
}
