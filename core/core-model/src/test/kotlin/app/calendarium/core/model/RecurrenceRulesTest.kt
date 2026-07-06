package app.calendarium.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceRulesTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `null or blank rrule parses to NONE`() {
        assertEquals(RecurrenceSpec(Frequency.NONE), RecurrenceRules.parse(null))
        assertEquals(RecurrenceSpec(Frequency.NONE), RecurrenceRules.parse("   "))
    }

    @Test
    fun `simple weekly rule parses`() {
        val spec = RecurrenceRules.parse("FREQ=WEEKLY")
        assertEquals(Frequency.WEEKLY, spec.frequency)
        assertEquals(1, spec.interval)
        assertNull(spec.count)
        assertNull(spec.until)
    }

    @Test
    fun `interval and byday parse`() {
        val spec = RecurrenceRules.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR")
        assertEquals(Frequency.WEEKLY, spec.frequency)
        assertEquals(2, spec.interval)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            spec.byWeekday,
        )
    }

    @Test
    fun `count parses`() {
        val spec = RecurrenceRules.parse("FREQ=MONTHLY;COUNT=10")
        assertEquals(Frequency.MONTHLY, spec.frequency)
        assertEquals(10, spec.count)
    }

    @Test
    fun `until date and timed forms both reduce to a LocalDate`() {
        val allDay = RecurrenceRules.parse("FREQ=DAILY;UNTIL=20260101")
        assertEquals(LocalDate.of(2026, 1, 1), allDay.until)

        val timed = RecurrenceRules.parse("FREQ=DAILY;UNTIL=20260101T000000Z")
        assertEquals(LocalDate.of(2026, 1, 1), timed.until)
    }

    @Test
    fun `nth weekday prefix is stripped but weekday kept`() {
        val spec = RecurrenceRules.parse("FREQ=MONTHLY;BYDAY=-1FR")
        assertEquals(setOf(DayOfWeek.FRIDAY), spec.byWeekday)
    }

    @Test
    fun `build of a plain frequency is just FREQ`() {
        assertEquals(
            "FREQ=WEEKLY",
            RecurrenceRules.build(RecurrenceSpec(Frequency.WEEKLY), allDay = false, zone = berlin),
        )
    }

    @Test
    fun `NONE builds to null`() {
        assertNull(RecurrenceRules.build(RecurrenceSpec(Frequency.NONE), allDay = false, zone = berlin))
    }

    @Test
    fun `build emits INTERVAL only above 1`() {
        assertEquals(
            "FREQ=DAILY;INTERVAL=3",
            RecurrenceRules.build(
                RecurrenceSpec(Frequency.DAILY, interval = 3),
                allDay = false,
                zone = berlin,
            ),
        )
    }

    @Test
    fun `build orders BYDAY Monday through Sunday`() {
        val out = RecurrenceRules.build(
            RecurrenceSpec(
                Frequency.WEEKLY,
                byWeekday = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ),
            allDay = false,
            zone = berlin,
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", out)
    }

    @Test
    fun `build COUNT beats UNTIL when both present`() {
        val out = RecurrenceRules.build(
            RecurrenceSpec(
                Frequency.DAILY,
                count = 5,
                until = LocalDate.of(2026, 1, 1),
            ),
            allDay = false,
            zone = berlin,
        )
        assertEquals("FREQ=DAILY;COUNT=5", out)
    }

    @Test
    fun `build UNTIL for all-day is a DATE`() {
        val out = RecurrenceRules.build(
            RecurrenceSpec(Frequency.DAILY, until = LocalDate.of(2026, 1, 5)),
            allDay = true,
            zone = berlin,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260105", out)
    }

    @Test
    fun `build UNTIL for timed is a UTC instant at end of local day`() {
        // End of 2026-01-05 in Berlin (UTC+1 in winter) = 2026-01-05T22:59:59Z
        val out = RecurrenceRules.build(
            RecurrenceSpec(Frequency.DAILY, until = LocalDate.of(2026, 1, 5)),
            allDay = false,
            zone = berlin,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260105T225959Z", out)
    }

    @Test
    fun `full custom weekly rule builds in canonical order`() {
        val out = RecurrenceRules.build(
            RecurrenceSpec(
                frequency = Frequency.WEEKLY,
                interval = 2,
                count = 8,
                byWeekday = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            ),
            allDay = false,
            zone = berlin,
        )
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=8;BYDAY=TU,TH", out)
    }

    @Test
    fun `isCustom flags non-trivial rules`() {
        assert(!RecurrenceSpec(Frequency.WEEKLY).isCustom)
        assert(RecurrenceSpec(Frequency.WEEKLY, interval = 2).isCustom)
        assert(RecurrenceSpec(Frequency.WEEKLY, count = 5).isCustom)
        assert(RecurrenceSpec(Frequency.WEEKLY, byWeekday = setOf(DayOfWeek.MONDAY)).isCustom)
        assert(!RecurrenceSpec(Frequency.MONTHLY).isCustom)
    }
}
