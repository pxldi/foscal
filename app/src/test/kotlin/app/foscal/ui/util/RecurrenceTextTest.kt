package app.foscal.ui.util

import app.foscal.R
import app.foscal.core.model.Frequency
import app.foscal.core.model.RecurrenceSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.util.Locale

class RecurrenceTextTest {

    private val uk = Locale.UK

    private fun summary(
        frequency: Frequency,
        interval: Int = 1,
        weekdays: List<java.time.DayOfWeek> = emptyList(),
        count: Int? = null,
        until: LocalDate? = null,
    ) = RecurrenceSummary(frequency, interval, weekdays, count, until)

    @Test
    fun `a rule with no summary is a custom repeat`() {
        assertEquals(uiText(R.string.repeat_custom), recurrenceText(null, uk))
    }

    @Test
    fun `an interval of one is one word, and more is counted`() {
        assertEquals(uiText(R.string.repeat_daily), recurrenceText(summary(Frequency.DAILY), uk))
        assertEquals(
            uiPlural(R.plurals.repeat_every_months, 2),
            recurrenceText(summary(Frequency.MONTHLY, interval = 2), uk),
        )
    }

    @Test
    fun `weekdays, then the end, wrap what came before`() {
        val days = uiText(R.string.repeat_on_days, uiPlural(R.plurals.repeat_every_weeks, 2), "Mon, Wed")
        assertEquals(
            uiPlural(R.plurals.repeat_times, 5, days, 5),
            recurrenceText(summary(Frequency.WEEKLY, 2, listOf(MONDAY, WEDNESDAY), count = 5), uk),
        )
        assertEquals(
            uiText(R.string.repeat_once, uiText(R.string.repeat_daily)),
            recurrenceText(summary(Frequency.DAILY, count = 1), uk),
        )
        assertEquals(
            uiText(R.string.repeat_until, uiText(R.string.repeat_weekly), "3 Nov 2026"),
            recurrenceText(summary(Frequency.WEEKLY, until = LocalDate.of(2026, 11, 3)), uk),
        )
    }

    @Test
    fun `weekday names come from the locale`() {
        assertEquals(
            uiText(R.string.repeat_on_days, uiText(R.string.repeat_weekly), "Mo., Mi."),
            recurrenceText(summary(Frequency.WEEKLY, weekdays = listOf(MONDAY, WEDNESDAY)), Locale.GERMANY),
        )
    }
}
