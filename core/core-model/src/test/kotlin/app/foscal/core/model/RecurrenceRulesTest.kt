package app.foscal.core.model

import java.time.DayOfWeek
import java.time.Instant
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

    @Test
    fun `truncateBefore returns null for non-recurring`() {
        assertNull(RecurrenceRules.truncateBefore(null, Instant.parse("2026-01-05T00:00:00Z"), allDay = false))
        assertNull(RecurrenceRules.truncateBefore("FREQ=NONE", Instant.parse("2026-01-05T00:00:00Z"), allDay = false))
    }

    @Test
    fun `truncateBefore for timed rule sets UNTIL one second before split`() {
        val split = Instant.parse("2026-01-05T09:00:00Z")
        val out = RecurrenceRules.truncateBefore("FREQ=DAILY", split, allDay = false)
        assertEquals("FREQ=DAILY;UNTIL=20260105T085959Z", out)
    }

    @Test
    fun `truncateBefore for all-day rule sets UNTIL to previous UTC day`() {
        val split = Instant.parse("2026-01-05T00:00:00Z")
        val out = RecurrenceRules.truncateBefore("FREQ=DAILY", split, allDay = true)
        assertEquals("FREQ=DAILY;UNTIL=20260104", out)
    }

    @Test
    fun `truncateBefore preserves INTERVAL and BYDAY and drops COUNT`() {
        val split = Instant.parse("2026-01-05T09:00:00Z")
        val out = RecurrenceRules.truncateBefore(
            "FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,WE,FR",
            split,
            allDay = false,
        )
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;UNTIL=20260105T085959Z", out)
    }

    @Test
    fun `rebaseFollowing returns null for non-recurring`() {
        assertNull(RecurrenceRules.rebaseFollowing(null, 3))
        assertNull(RecurrenceRules.rebaseFollowing("FREQ=NONE", 3))
    }

    @Test
    fun `rebaseFollowing leaves an open-ended rule unchanged`() {
        assertEquals(
            "FREQ=DAILY",
            RecurrenceRules.rebaseFollowing("FREQ=DAILY", 4),
        )
    }

    @Test
    fun `rebaseFollowing subtracts occurrencesBeforeSplit from COUNT`() {
        assertEquals(
            "FREQ=DAILY;COUNT=6",
            RecurrenceRules.rebaseFollowing("FREQ=DAILY;COUNT=10", 4),
        )
    }

    @Test
    fun `rebaseFollowing clamps a COUNT that would go below one`() {
        assertEquals(
            "FREQ=DAILY;COUNT=1",
            RecurrenceRules.rebaseFollowing("FREQ=DAILY;COUNT=2", 10),
        )
    }

    @Test
    fun `rebaseFollowing preserves INTERVAL, BYDAY, and UNTIL`() {
        val out = RecurrenceRules.rebaseFollowing(
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR",
            3,
        )
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR", out)
    }

    // --- splits keep what the app does not model --------------------------------------------

    private val split = Instant.parse("2026-03-27T09:00:00Z")

    @Test
    fun `truncateBefore keeps an ordinal BYDAY`() {
        assertEquals(
            "FREQ=MONTHLY;BYDAY=-1FR;UNTIL=20260327T085959Z",
            RecurrenceRules.truncateBefore("FREQ=MONTHLY;BYDAY=-1FR", split, allDay = false),
        )
    }

    @Test
    fun `truncateBefore keeps a negative BYMONTHDAY`() {
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=-1;UNTIL=20260327T085959Z",
            RecurrenceRules.truncateBefore("FREQ=MONTHLY;BYMONTHDAY=-1", split, allDay = false),
        )
    }

    @Test
    fun `truncateBefore keeps a yearly BYMONTH with an ordinal BYDAY`() {
        assertEquals(
            "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH;UNTIL=20260327T085959Z",
            RecurrenceRules.truncateBefore("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH", split, allDay = false),
        )
    }

    @Test
    fun `truncateBefore keeps BYSETPOS and WKST and drops only the end`() {
        assertEquals(
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;WKST=SU;UNTIL=20260327T085959Z",
            RecurrenceRules.truncateBefore(
                "FREQ=MONTHLY;COUNT=12;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;UNTIL=20270101T000000Z;WKST=SU",
                split,
                allDay = false,
            ),
        )
    }

    @Test
    fun `truncateBefore handles a frequency the editor does not model`() {
        // Falling through to the untouched rule left both series generating the split occurrence.
        assertEquals(
            "FREQ=HOURLY;INTERVAL=4;UNTIL=20260327T085959Z",
            RecurrenceRules.truncateBefore("FREQ=HOURLY;INTERVAL=4", split, allDay = false),
        )
    }

    @Test
    fun `rebaseFollowing keeps unmodelled parts verbatim`() {
        for (rule in listOf(
            "FREQ=MONTHLY;BYDAY=-1FR",
            "FREQ=MONTHLY;BYMONTHDAY=-1",
            "FREQ=YEARLY;BYMONTH=11;BYDAY=4TH",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;WKST=SU",
        )) {
            assertEquals(rule, RecurrenceRules.rebaseFollowing(rule, 3))
        }
    }

    @Test
    fun `rebaseFollowing replaces COUNT where it stands`() {
        assertEquals(
            "FREQ=MONTHLY;COUNT=9;BYDAY=-1FR",
            RecurrenceRules.rebaseFollowing("FREQ=MONTHLY;COUNT=12;BYDAY=-1FR", 3),
        )
    }

    @Test
    fun `rebaseFollowing keeps a timed UNTIL exactly`() {
        // Rebuilding it from a date moved it to the end of that UTC day in the device zone, which
        // west of UTC is the next day.
        val rule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20260106T045959Z"
        assertEquals(rule, RecurrenceRules.rebaseFollowing(rule, 2))
    }

    // --- UNTIL survives an editor round trip west of UTC --------------------------------------

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `a timed UNTIL reads back as the date it was built from in winter`() {
        val until = LocalDate.of(2026, 1, 5)
        val rule = RecurrenceRules.build(
            RecurrenceSpec(Frequency.DAILY, until = until),
            allDay = false,
            zone = newYork,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260106T045959Z", rule)
        assertEquals(until, RecurrenceRules.parse(rule, newYork).until)
    }

    @Test
    fun `a timed UNTIL reads back as the date it was built from in summer`() {
        val until = LocalDate.of(2026, 7, 5)
        val rule = RecurrenceRules.build(
            RecurrenceSpec(Frequency.DAILY, until = until),
            allDay = false,
            zone = newYork,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260706T035959Z", rule)
        assertEquals(until, RecurrenceRules.parse(rule, newYork).until)
        // And building again from what was read gives the same rule, so saves do not creep.
        assertEquals(
            rule,
            RecurrenceRules.build(RecurrenceRules.parse(rule, newYork), allDay = false, zone = newYork),
        )
    }

    @Test
    fun `an all-day UNTIL is a date in every zone`() {
        assertEquals(
            LocalDate.of(2026, 1, 5),
            RecurrenceRules.parse("FREQ=DAILY;UNTIL=20260105", newYork).until,
        )
    }
}
