package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class RecurrenceSummaryTest {

    private val uk = Locale.UK
    private val utc = ZoneOffset.UTC

    private fun describe(rule: String, zone: ZoneId = utc, locale: Locale = uk) =
        RecurrenceSummary.describe(rule, zone, locale)

    @Test
    fun `a plain frequency reads as one word`() {
        assertEquals("Daily", describe("FREQ=DAILY"))
        assertEquals("Weekly", describe("FREQ=WEEKLY"))
        assertEquals("Monthly", describe("FREQ=MONTHLY"))
        assertEquals("Yearly", describe("FREQ=YEARLY"))
    }

    @Test
    fun `an interval is spelled out`() {
        assertEquals("Every 3 days", describe("FREQ=DAILY;INTERVAL=3"))
        assertEquals("Every 2 months", describe("FREQ=MONTHLY;INTERVAL=2"))
        assertEquals("Yearly", describe("FREQ=YEARLY;INTERVAL=1"))
    }

    @Test
    fun `weekdays follow the interval in the locale's week order`() {
        assertEquals("Every 2 weeks on Mon, Wed", describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=WE,MO"))
        assertEquals("Weekly on Mon, Sun", describe("FREQ=WEEKLY;BYDAY=SU,MO"))
        assertEquals("Weekly on Sun, Mon", describe("FREQ=WEEKLY;BYDAY=SU,MO", locale = Locale.US))
    }

    @Test
    fun `weekday names come from the locale`() {
        assertEquals("Weekly on Mo., Mi.", describe("FREQ=WEEKLY;BYDAY=MO,WE", locale = Locale.GERMANY))
    }

    @Test
    fun `a count or an end date closes the summary`() {
        assertEquals("Monthly, 10 times", describe("FREQ=MONTHLY;COUNT=10"))
        assertEquals("Daily, once", describe("FREQ=DAILY;COUNT=1"))
        assertEquals("Weekly until 3 Nov 2026", describe("FREQ=WEEKLY;UNTIL=20261103"))
        assertEquals(
            "Every 2 weeks on Tue, 5 times",
            describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;COUNT=5"),
        )
    }

    @Test
    fun `a timed end is shown as the date in the event's zone`() {
        // "Until 5 January" in New York is stored as the end of that day in UTC, on the 6th.
        val newYork = ZoneId.of("America/New_York")
        assertEquals("Daily until 5 Jan 2026", describe("FREQ=DAILY;UNTIL=20260106T045959Z", newYork))
    }

    @Test
    fun `WKST does not make a rule custom`() {
        assertEquals("Weekly on Mon, Fri", describe("FREQ=WEEKLY;WKST=SU;BYDAY=MO,FR"))
    }

    @Test
    fun `rules the app does not model are called custom`() {
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
            assertEquals(rule, RecurrenceSummary.CUSTOM, describe(rule))
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
