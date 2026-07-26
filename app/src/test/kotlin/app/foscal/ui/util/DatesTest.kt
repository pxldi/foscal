package app.foscal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class DatesTest {

    @Test
    fun `weekday labels start at the requested day`() {
        val labels = Dates.weekStartLabels(Locale.ENGLISH)
        assertEquals(7, labels.size)
        assertEquals(
            DayOfWeek.MONDAY.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ENGLISH),
            labels.first(),
        )
    }

    @Test
    fun `weekday labels follow the locale they are given`() {
        // Regression: these came from a formatter frozen at class-init, so the strip kept the old
        // language after the user switched until the process restarted.
        assertNotEquals(
            Dates.weekStartLabels(Locale.ENGLISH),
            Dates.weekStartLabels(Locale.forLanguageTag("ru")),
        )
    }

    @Test
    fun `a sunday-first week rotates the labels rather than reordering the days`() {
        val monday = Dates.weekStartLabels(Locale.ENGLISH, DayOfWeek.MONDAY)
        val sunday = Dates.weekStartLabels(Locale.ENGLISH, DayOfWeek.SUNDAY)
        assertEquals(monday.last(), sunday.first())
        assertEquals(monday.dropLast(1), sunday.drop(1))
    }
}
