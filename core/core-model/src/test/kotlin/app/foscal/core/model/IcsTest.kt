package app.foscal.core.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val stamp = Instant.parse("2026-01-01T00:00:00Z")

    private fun timed(
        title: String = "Standup",
        start: String = "2026-07-26T09:00:00Z",
        end: String = "2026-07-26T09:30:00Z",
        location: String? = null,
        description: String? = null,
        rrule: String? = null,
        reminders: List<Int> = emptyList(),
    ) = IcsEvent(
        title = title,
        start = Instant.parse(start),
        end = Instant.parse(end),
        allDay = false,
        location = location,
        description = description,
        rrule = rrule,
        reminderMinutes = reminders,
    )

    private fun writeRead(event: IcsEvent): IcsEvent =
        Ics.read(Ics.write(listOf(event), stamp), berlin).single()

    // ------------------------------------------------------------- structure

    @Test
    fun `document is wrapped in a VCALENDAR with CRLF line endings`() {
        val text = Ics.write(listOf(timed()), stamp)
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(text.trimEnd().endsWith("END:VCALENDAR"))
        assertTrue("VERSION:2.0\r\n" in text)
        assertFalse("\n\n" in text.replace("\r\n", "\n").trimEnd())
    }

    @Test
    fun `every event carries a UID and a DTSTAMP`() {
        val text = Ics.write(listOf(timed()), stamp)
        assertTrue("DTSTAMP:20260101T000000Z" in text)
        assertTrue(text.lines().any { it.startsWith("UID:") })
    }

    @Test
    fun `synthetic UID is stable across exports of the same event`() {
        val first = Ics.write(listOf(timed()), stamp)
        val second = Ics.write(listOf(timed()), stamp.plusSeconds(86_400))
        val uidOf = { text: String -> text.lines().first { it.startsWith("UID:") } }
        assertEquals(uidOf(first), uidOf(second))
    }

    @Test
    fun `an existing UID is preserved rather than replaced`() {
        val event = timed().copy(uid = "abc-123@example.com")
        assertTrue("UID:abc-123@example.com" in Ics.write(listOf(event), stamp))
        assertEquals("abc-123@example.com", writeRead(event).uid)
    }

    // ----------------------------------------------------------- round trips

    @Test
    fun `timed event round trips`() {
        val event = timed(location = "Room 3", description = "Daily sync")
        val back = writeRead(event)
        assertEquals("Standup", back.title)
        assertEquals(Instant.parse("2026-07-26T09:00:00Z"), back.start)
        assertEquals(Instant.parse("2026-07-26T09:30:00Z"), back.end)
        assertFalse(back.allDay)
        assertEquals("Room 3", back.location)
        assertEquals("Daily sync", back.description)
    }

    @Test
    fun `all-day event keeps its exclusive end and stays on the same date west of UTC`() {
        val event = IcsEvent(
            title = "Holiday",
            // The provider stores all-day events at UTC midnight; a one-day event ends the
            // following midnight.
            start = Instant.parse("2026-07-26T00:00:00Z"),
            end = Instant.parse("2026-07-27T00:00:00Z"),
            allDay = true,
        )
        val text = Ics.write(listOf(event), stamp)
        assertTrue("DTSTART;VALUE=DATE:20260726" in text)
        assertTrue("DTEND;VALUE=DATE:20260727" in text)

        // Read back in a zone behind UTC: an all-day value resolved in the device zone would
        // land on the 25th.
        val back = Ics.read(text, ZoneId.of("America/Los_Angeles")).single()
        assertTrue(back.allDay)
        assertEquals(Instant.parse("2026-07-26T00:00:00Z"), back.start)
        assertEquals(Instant.parse("2026-07-27T00:00:00Z"), back.end)
    }

    @Test
    fun `an all-day event stored with an equal end exports as a one-day span`() {
        // The provider really does hold all-day rows with DTEND = DTSTART; a DATE-valued DTEND
        // equal to DTSTART describes an event covering no days and is rejected by strict parsers.
        val event = IcsEvent(
            title = "Public holiday",
            start = Instant.parse("2026-07-30T00:00:00Z"),
            end = Instant.parse("2026-07-30T00:00:00Z"),
            allDay = true,
        )
        val text = Ics.write(listOf(event), stamp)
        assertTrue("DTSTART;VALUE=DATE:20260730" in text)
        assertTrue("DTEND;VALUE=DATE:20260731" in text)
        assertEquals(
            Instant.parse("2026-07-31T00:00:00Z"),
            Ics.read(text, berlin).single().end,
        )
    }

    @Test
    fun `a multi-day all-day span is left alone`() {
        val event = IcsEvent(
            title = "Conference",
            start = Instant.parse("2026-07-30T00:00:00Z"),
            end = Instant.parse("2026-08-03T00:00:00Z"),
            allDay = true,
        )
        assertTrue("DTEND;VALUE=DATE:20260803" in Ics.write(listOf(event), stamp))
    }

    @Test
    fun `recurrence rule round trips verbatim`() {
        val back = writeRead(timed(rrule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE", back.rrule)
    }

    @Test
    fun `reminders round trip through VALARM triggers`() {
        val text = Ics.write(listOf(timed(reminders = listOf(15, 60, 1440, 90))), stamp)
        assertTrue("TRIGGER;RELATED=START:-PT15M" in text)
        assertTrue("TRIGGER;RELATED=START:-PT1H" in text)
        assertTrue("TRIGGER;RELATED=START:-PT1H30M" in text)
        assertTrue("TRIGGER;RELATED=START:-P1D" in text)
        assertEquals(listOf(15, 60, 90, 1440), Ics.read(text, berlin).single().reminderMinutes)
    }

    @Test
    fun `zero-minute reminder round trips as PT0S`() {
        val text = Ics.write(listOf(timed(reminders = listOf(0))), stamp)
        assertTrue("TRIGGER;RELATED=START:PT0S" in text)
        assertEquals(listOf(0), Ics.read(text, berlin).single().reminderMinutes)
    }

    // -------------------------------------------------------------- escaping

    @Test
    fun `special characters survive escaping`() {
        val event = timed(
            title = "Lunch; with Bob, Alice",
            description = "Line one\nLine two \\ backslash",
        )
        val text = Ics.write(listOf(event), stamp)
        assertTrue("SUMMARY:Lunch\\; with Bob\\, Alice" in text)
        assertFalse("Line one\r\nLine two" in text)

        val back = Ics.read(text, berlin).single()
        assertEquals("Lunch; with Bob, Alice", back.title)
        assertEquals("Line one\nLine two \\ backslash", back.description)
    }

    @Test
    fun `long lines fold at 75 octets and unfold back to the original`() {
        val long = "A".repeat(400)
        val text = Ics.write(listOf(timed(title = long)), stamp)
        val physical = text.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(physical.any { it.startsWith(" ") })
        physical.forEach {
            assertTrue("line too long: ${it.length}", it.toByteArray(Charsets.UTF_8).size <= 75)
        }
        assertEquals(long, Ics.read(text, berlin).single().title)
    }

    @Test
    fun `folding counts octets so multi-byte titles stay within the limit`() {
        val text = Ics.write(listOf(timed(title = "Ünïcödé — ☕ ".repeat(20))), stamp)
        text.split("\r\n").filter { it.isNotEmpty() }.forEach {
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75)
        }
        assertEquals("Ünïcödé — ☕ ".repeat(20), Ics.read(text, berlin).single().title)
    }

    // --------------------------------------------------------------- parsing

    @Test
    fun `TZID is resolved and preserved`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Berlin meeting
            DTSTART;TZID=Europe/Berlin:20260726T090000
            DTEND;TZID=Europe/Berlin:20260726T100000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, ZoneId.of("UTC")).single()
        // 09:00 Berlin in July is 07:00 UTC.
        assertEquals(Instant.parse("2026-07-26T07:00:00Z"), event.start)
        assertEquals("Europe/Berlin", event.timezone)
    }

    @Test
    fun `a UTC time reports UTC rather than no zone`() {
        // Falling back to the device zone here would re-anchor a recurring series' wall time.
        val back = writeRead(timed(rrule = "FREQ=WEEKLY"))
        assertEquals("UTC", back.timezone)
    }

    @Test
    fun `floating time resolves in the fallback zone`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Floating
            DTSTART:20260726T090000
            DTEND:20260726T100000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(
            Instant.parse("2026-07-26T07:00:00Z"),
            Ics.read(text, berlin).single().start,
        )
    }

    @Test
    fun `unknown TZID falls back instead of dropping the event`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Bad zone
            DTSTART;TZID=Mars/Olympus:20260726T090000
            DTEND;TZID=Mars/Olympus:20260726T100000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, berlin).single()
        assertEquals(Instant.parse("2026-07-26T07:00:00Z"), event.start)
        assertNull(event.timezone)
    }

    @Test
    fun `DURATION substitutes for a missing DTEND`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Workshop
            DTSTART:20260726T090000Z
            DURATION:PT1H30M
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, berlin).single()
        assertEquals(Instant.parse("2026-07-26T10:30:00Z"), event.end)
    }

    @Test
    fun `a DATE start with no end lasts exactly one day`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Day off
            DTSTART;VALUE=DATE:20260726
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, berlin).single()
        assertTrue(event.allDay)
        assertEquals(Instant.parse("2026-07-27T00:00:00Z"), event.end)
    }

    @Test
    fun `VTIMEZONE inner DTSTART does not overwrite the event's`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            BEGIN:DAYLIGHT
            DTSTART:19700329T020000
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            SUMMARY:Real event
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, berlin).single()
        assertEquals("Real event", event.title)
        assertEquals(Instant.parse("2026-07-26T09:00:00Z"), event.start)
    }

    @Test
    fun `non-VEVENT components are skipped`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VTODO
            SUMMARY:Buy milk
            DTSTART:20260726T090000Z
            END:VTODO
            BEGIN:VEVENT
            SUMMARY:Real event
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val events = Ics.read(text, berlin)
        assertEquals(1, events.size)
        assertEquals("Real event", events.single().title)
    }

    @Test
    fun `alarms that cannot be represented as minutes-before are dropped`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Meeting
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT10M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:20260726T080000Z
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:PT30M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(listOf(10), Ics.read(text, berlin).single().reminderMinutes)
    }

    @Test
    fun `week durations parse`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Sprint
            DTSTART:20260726T090000Z
            DURATION:P1W
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(
            Instant.parse("2026-08-02T09:00:00Z"),
            Ics.read(text, berlin).single().end,
        )
    }

    @Test
    fun `folded input from another producer is unfolded`() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:A very long tit\r\n le that was folded" +
            "\r\nDTSTART:20260726T090000Z\r\nDTEND:20260726T093000Z\r\nEND:VEVENT\r\nEND:VCALENDAR"
        assertEquals("A very long title that was folded", Ics.read(text, berlin).single().title)
    }

    @Test
    fun `quoted parameter containing a colon does not split the line early`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY;X-LABEL="a:b":Tricky
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals("Tricky", Ics.read(text, berlin).single().title)
    }

    @Test
    fun `an event without DTSTART is skipped rather than failing the file`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:No start
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Fine
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(listOf("Fine"), Ics.read(text, berlin).map { it.title })
    }

    @Test
    fun `an end before the start is clamped instead of producing a negative span`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Backwards
            DTSTART:20260726T090000Z
            DTEND:20260726T080000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val event = Ics.read(text, berlin).single()
        assertEquals(event.start, event.end)
    }

    @Test
    fun `an untitled event gets a placeholder rather than an empty row`() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART:20260726T090000Z
            DTEND:20260726T093000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals("(No title)", Ics.read(text, berlin).single().title)
    }

    @Test
    fun `garbage input yields no events instead of throwing`() {
        assertTrue(Ics.read("", berlin).isEmpty())
        assertTrue(Ics.read("not a calendar at all", berlin).isEmpty())
        assertTrue(Ics.read("BEGIN:VCALENDAR\r\nEND:VCALENDAR", berlin).isEmpty())
    }

    @Test
    fun `multiple events round trip in order`() {
        val events = listOf(
            timed(title = "One"),
            timed(title = "Two", start = "2026-07-27T09:00:00Z", end = "2026-07-27T10:00:00Z"),
        )
        assertEquals(
            listOf("One", "Two"),
            Ics.read(Ics.write(events, stamp), berlin).map { it.title },
        )
    }
}
