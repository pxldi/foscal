package app.foscal.ui.week

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SpanTitleTest {

    private val en = Locale.US

    @Test
    fun `this year's ranges carry no year`() {
        assertEquals("Sep 21 – 27", formatSpanRange(LocalDate.of(2026, 9, 21), 7, en, 2026))
        assertEquals("Aug 31 – Sep 6", formatSpanRange(LocalDate.of(2026, 8, 31), 7, en, 2026))
        assertEquals("Wed, Sep 23", formatSpanRange(LocalDate.of(2026, 9, 23), 1, en, 2026))
    }

    @Test
    fun `another year's ranges name it`() {
        assertEquals("Sep 20 – 26, 2027", formatSpanRange(LocalDate.of(2027, 9, 20), 7, en, 2026))
        assertEquals("Aug 30 – Sep 5, 2027", formatSpanRange(LocalDate.of(2027, 8, 30), 7, en, 2026))
        assertEquals("Thu, Sep 23, 2027", formatSpanRange(LocalDate.of(2027, 9, 23), 1, en, 2026))
    }

    @Test
    fun `a range across new year names both years`() {
        assertEquals(
            "Dec 28, 2026 – Jan 3, 2027",
            formatSpanRange(LocalDate.of(2026, 12, 28), 7, en, 2026),
        )
    }
}
