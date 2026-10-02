package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class RecurrenceSummaryTest {

    private val uk = Locale.UK
    private val utc = ZoneOffset.UTC

    private fun of(rule: String, zone: ZoneId = utc, locale: Locale = uk) =
        RecurrenceSummary.of(rule, zone, locale)

    @Test
    fun `a plain frequency carries no interval, days or end`() {
        assertEquals(RecurrenceSummary(Frequency.DAILY, 1, emptyList(), null, null), of("FREQ=DAILY"))
        assertEquals(Frequency.WEEKLY, of("FREQ=WEEKLY")?.frequency)
        assertEquals(Frequency.MONTHLY, of("FREQ=MONTHLY")?.frequency)
        assertEquals(Frequency.YEARLY, of("FREQ=YEARLY")?.frequency)
    }

    @Test
    fun `an interval is kept`() {
        assertEquals(3, of("FREQ=DAILY;INTERVAL=3")?.interval)
        assertEquals(1, of("FREQ=YEARLY;INTERVAL=1")?.interval)
    }

    @Test
    fun `weekdays follow the locale's week order`() {
        assertEquals(listOf(MONDAY, WEDNESDAY), of("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO")?.weekdays)
        assertEquals(listOf(MONDAY, SUNDAY), of("FREQ=WEEKLY;BYDAY=SU,MO")?.weekdays)
        assertEquals(listOf(SUNDAY, MONDAY), of("FREQ=WEEKLY;BYDAY=SU,MO", locale = Locale.US)?.weekdays)
    }

    @Test
    fun `a count or an end date is carried`() {
        assertEquals(10, of("FREQ=MONTHLY;COUNT=10")?.count)
        assertEquals(1, of("FREQ=DAILY;COUNT=1")?.count)
        assertEquals(LocalDate.of(2026, 11, 3), of("FREQ=WEEKLY;UNTIL=20261103")?.until)
        assertEquals(
            RecurrenceSummary(Frequency.WEEKLY, 2, listOf(TUESDAY), 5, null),
            of("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;COUNT=5"),
        )
    }

    @Test
    fun `a timed end is the date in the event's zone`() {
        // "Until 5 January" in New York is stored as the end of that day in UTC, on the 6th.
        val newYork = ZoneId.of("America/New_York")
        assertEquals(LocalDate.of(2026, 1, 5), of("FREQ=DAILY;UNTIL=20260106T045959Z", newYork)?.until)
    }

    @Test
    fun `WKST does not make a rule custom`() {
        assertEquals(listOf(MONDAY, FRIDAY), of("FREQ=WEEKLY;WKST=SU;BYDAY=MO,FR")?.weekdays)
    }

    @Test
    fun `rules the app does not model have no summary`() {
        listOf(
            "FREQ=MONTHLY;BYDAY=-1FR",
            "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1",
            "FREQ=MONTHLY;BYMONTHDAY=15",
            "FREQ=YEARLY;BYMONTH=3",
            "FREQ=HOURLY;INTERVAL=4",
            "FREQ=DAILY;BYDAY=MO,TU",
            "FREQ=WEEKLY;COUNT=0",
            "FREQ=WEEKLY;INTERVAL=x",
            "FREQ=WEEKLY;COUNT=3;UNTIL=20261103",
            "FREQ=WEEKLY;UNTIL=soon",
            "INTERVAL=2",
            "",
        ).forEach { rule ->
            assertNull(rule, of(rule))
        }
    }

    @Test
    fun `isModelled accepts only rules that parse captures in full`() {
        assertTrue(RecurrenceRules.isModelled("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20261103T225959Z"))
        assertTrue(RecurrenceRules.isModelled("freq=monthly;count=4"))
        assertFalse(RecurrenceRules.isModelled("FREQ=WEEKLY;BYDAY=1MO"))
        assertFalse(RecurrenceRules.isModelled("FREQ=WEEKLY;FREQ=DAILY"))
        assertFalse(RecurrenceRules.isModelled(null))
    }
}
