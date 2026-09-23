package app.foscal.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsZoneWriterTest {

    private val vienna = ZoneId.of("Europe/Vienna")
    private val stamp = Instant.parse("2026-01-01T00:00:00Z")

    /** Weekly at 10:00 Vienna, first occurrence in summer time. */
    private val weekly = IcsEvent(
        title = "Jour fixe",
        start = ZonedDateTime.of(2026, 7, 6, 10, 0, 0, 0, vienna).toInstant(),
        end = ZonedDateTime.of(2026, 7, 6, 11, 0, 0, 0, vienna).toInstant(),
        allDay = false,
        timezone = "Europe/Vienna",
        rrule = "FREQ=WEEKLY;BYDAY=MO",
        uid = "jour-fixe@example.com",
    )

    @Test
    fun `a zoned series is written as wall time with its zone`() {
        val text = Ics.write(listOf(weekly), stamp)
        assertTrue("DTSTART;TZID=Europe/Vienna:20260706T100000\r\n" in text)
        assertTrue("DTEND;TZID=Europe/Vienna:20260706T110000\r\n" in text)
        assertTrue("BEGIN:VTIMEZONE\r\nTZID:Europe/Vienna\r\n" in text)
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" in text)
        assertTrue("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" in text)
    }

    @Test
    fun `a Vienna series exported in summer still starts at 10 in November`() {
        // Read in a zone that is neither the event's nor UTC and has its own DST dates, so a
        // result that leaned on the fallback zone would come out wrong.
        val back = Ics.read(Ics.write(listOf(weekly), stamp), ZoneId.of("America/New_York")).single()
        assertEquals("Europe/Vienna", back.timezone)
        assertEquals(weekly.start, back.start)
        // The provider expands a series in its EVENT_TIMEZONE, which is what this does.
        val november = back.start.atZone(ZoneId.of(back.timezone!!)).plusWeeks(18)
        assertEquals(LocalDate.of(2026, 11, 9), november.toLocalDate())
        assertEquals(LocalTime.of(10, 0), november.toLocalTime())
    }

    @Test
    fun `exdates and recurrence ids use the event's zone`() {
        val exdate = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, vienna).toInstant()
        val override = weekly.copy(
            rrule = null,
            recurrenceId = ZonedDateTime.of(2026, 11, 16, 10, 0, 0, 0, vienna).toInstant(),
            start = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, vienna).toInstant(),
            end = ZonedDateTime.of(2026, 11, 16, 13, 0, 0, 0, vienna).toInstant(),
        )
        val text = Ics.write(listOf(weekly.copy(exdates = listOf(exdate)), override), stamp)
        assertTrue("EXDATE;TZID=Europe/Vienna:20261102T100000\r\n" in text)
        assertTrue("RECURRENCE-ID;TZID=Europe/Vienna:20261116T100000\r\n" in text)
        // One block per zone, however many events use it.
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(text).count())

        val back = Ics.read(text, ZoneId.of("UTC"))
        assertEquals(listOf(exdate), back.first().exdates)
        assertEquals(override.recurrenceId, back.last().recurrenceId)
    }

    @Test
    fun `the generated block alone gives a reader Vienna's offsets`() {
        // A reader that cannot resolve the name has only the block to go on. Renaming the zone and
        // dropping X-LIC-LOCATION leaves Foscal's own reader in exactly that position.
        val text = Ics.write(listOf(weekly), stamp)
            .replace("Europe/Vienna", "Custom")
            .replace("X-LIC-LOCATION:Custom\r\n", "")
        val back = Ics.read(text, ZoneId.of("UTC")).single()
        val zone = ZoneId.of(back.timezone!!)
        for (month in listOf(1, 4, 7, 11)) {
            val instant = LocalDateTime.of(2026, month, 15, 12, 0).atZone(vienna).toInstant()
            assertEquals(vienna.rules.getOffset(instant), zone.rules.getOffset(instant))
        }
        assertEquals(weekly.start, back.start)
    }

    @Test
    fun `UTC and fixed offsets stay in UTC with no block`() {
        for (zone in listOf("UTC", "GMT+05:30", "Etc/GMT-3", null, "Not/AZone")) {
            val text = Ics.write(listOf(weekly.copy(timezone = zone)), stamp)
            assertTrue(zone.toString(), "DTSTART:20260706T080000Z\r\n" in text)
            assertFalse(zone.toString(), "VTIMEZONE" in text)
        }
    }

    @Test
    fun `a zone that dropped DST gets one standard observance`() {
        val lines = IcsZoneWriter.lines(ZoneId.of("Asia/Tokyo"))
        assertEquals(1, lines.count { it == "BEGIN:STANDARD" })
        assertTrue("TZOFFSETTO:+0900" in lines)
        assertFalse(lines.any { it.startsWith("RRULE") })
    }

    @Test
    fun `all-day events never name a zone`() {
        val allDay = weekly.copy(
            allDay = true,
            start = Instant.parse("2026-07-06T00:00:00Z"),
            end = Instant.parse("2026-07-07T00:00:00Z"),
        )
        val text = Ics.write(listOf(allDay), stamp)
        assertTrue("DTSTART;VALUE=DATE:20260706\r\n" in text)
        assertFalse("VTIMEZONE" in text)
        assertNull(Ics.read(text, vienna).single().timezone)
    }

    @Test
    fun `every zone's yearly rule lands on the dates its transitions fall on`() {
        val mismatches = mutableListOf<String>()
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            for (rule in ZoneId.of(id).rules.transitionRules) {
                val rrule = IcsZoneWriter.recurrence(rule)
                for (year in 2020..2045) {
                    val expected = rule.createTransition(year).dateTimeBefore.toLocalDate()
                    val actual = expand(rrule, year)
                    if (actual != expected) mismatches += "$id $rrule $year: $actual != $expected"
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun `offsets with seconds keep them`() {
        assertEquals("+0100", IcsZoneWriter.offset(java.time.ZoneOffset.ofHours(1)))
        assertEquals("-0330", IcsZoneWriter.offset(java.time.ZoneOffset.ofHoursMinutes(-3, -30)))
        assertEquals("+005328", IcsZoneWriter.offset(java.time.ZoneOffset.ofTotalSeconds(3208)))
    }

    @Test
    fun `offsets use ASCII digits whatever the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("+0200", IcsZoneWriter.offset(java.time.ZoneOffset.ofHours(2)))
            val text = Ics.write(listOf(weekly), stamp)
            assertTrue("DTSTART;TZID=Europe/Vienna:20260706T100000\r\n" in text)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    /** The one date in [year] that a yearly BYMONTH rule of the forms the writer emits names. */
    private fun expand(rrule: String, year: Int): LocalDate? {
        val parts = rrule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        parts["BYYEARDAY"]?.let { list ->
            val dow = DayOfWeek.entries.first { it.name.startsWith(parts.getValue("BYDAY")) }
            val jan1 = LocalDate.of(year, 1, 1)
            return list.split(',').map { it.toInt() }
                .map { if (it > 0) jan1.plusDays(it - 1L) else jan1.plusYears(1).plusDays(it.toLong()) }
                .singleOrNull { it.dayOfWeek == dow }
        }
        val month = LocalDate.of(year, Month.of(parts.getValue("BYMONTH").toInt()), 1)
        val length = month.lengthOfMonth()
        val byDay = parts["BYDAY"]
        val monthDays = parts["BYMONTHDAY"]?.split(',')?.map { it.toInt() }
            ?.map { if (it > 0) it else length + 1 + it }
        if (byDay == null) return monthDays?.singleOrNull()?.let(month::withDayOfMonth)
        val match = Regex("^(-?\\d+)?([A-Z]{2})$").find(byDay)!!
        val dow = DayOfWeek.entries.first { it.name.startsWith(match.groupValues[2]) }
        val ordinal = match.groupValues[1].toIntOrNull()
        return when {
            ordinal != null && ordinal > 0 -> month.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dow))
            ordinal != null -> month.with(TemporalAdjusters.lastInMonth(dow)).minusWeeks((-ordinal - 1).toLong())
            else -> monthDays!!.filter { it in 1..length }.map(month::withDayOfMonth).singleOrNull { it.dayOfWeek == dow }
        }
    }
}
