package app.foscal.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files shaped like Outlook, Google Calendar and Apple Calendar exports, and what each app would
 * show for them.
 *
 * The fixtures in `src/test/resources/ics` are written by hand after each app's export format, not
 * captured from the apps themselves.
 */
class IcsInteropTest {

    /** A fallback nothing in the fixtures should need, so a test that lands on it fails loudly. */
    private val elsewhere = ZoneId.of("Pacific/Kiritimati")

    private fun fixture(name: String): Ics.Document =
        Ics.readDocument(javaClass.getResource("/ics/$name")!!.readText(), elsewhere)

    private fun at(text: String): Instant = Instant.parse(text)

    // ------------------------------------------------------------- Outlook

    @Test
    fun `outlook - a Windows TZID resolves to its IANA zone`() {
        val meeting = fixture("outlook.ics").events.first { it.title == "Quarterly planning" }
        assertEquals(at("2026-09-15T07:00:00Z"), meeting.start)
        assertEquals(at("2026-09-15T08:00:00Z"), meeting.end)
        assertEquals("Europe/Berlin", meeting.timezone)
        assertEquals(listOf(15), meeting.reminderMinutes)
        assertEquals("anna.berger@example.com", meeting.organizer?.email)
        assertEquals(listOf("me@example.com"), meeting.attendees.map { it.email })
        assertEquals(EventAccess.PUBLIC, meeting.access)
        assertEquals(EventAvailability.BUSY, meeting.availability)
        assertEquals("Room 4.12", meeting.location)
    }

    @Test
    fun `outlook - a customized zone is matched by its rules across the DST change`() {
        val events = fixture("outlook.ics").events
        val series = events.first { it.title == "Focus time" }
        val zone = ZoneId.of(assertNotNullAndGet(series.timezone))
        // Whatever zone it matched, it must follow the file's rules on both sides of 25 October.
        assertEquals(ZoneOffset.ofHours(2), zone.rules.getOffset(at("2026-10-05T08:00:00Z")))
        assertEquals(ZoneOffset.ofHours(1), zone.rules.getOffset(at("2026-11-02T09:00:00Z")))
        assertEquals(at("2026-10-05T08:00:00Z"), series.start)
        assertEquals(listOf(at("2026-10-19T08:00:00Z")), series.exdates)
        assertEquals(EventAccess.PRIVATE, series.access)
        assertEquals(EventAvailability.FREE, series.availability)

        val moved = events.first { it.isOverride }
        assertEquals(at("2026-10-26T09:00:00Z"), moved.recurrenceId)
        assertEquals(at("2026-10-26T12:00:00Z"), moved.start)
    }

    // ------------------------------------------------------------- Google

    @Test
    fun `google - cancelled events are left out and a cancelled occurrence becomes an EXDATE`() {
        val events = fixture("google.ics").events
        assertEquals(
            listOf("Team sync", "Team sync (afternoon)", "Nationalfeiertag", "Dentist"),
            events.map { it.title },
        )
        val series = events.first()
        assertEquals(at("2026-09-07T08:00:00Z"), series.start)
        assertEquals(
            listOf(at("2026-09-14T08:00:00Z"), at("2026-09-28T08:00:00Z")),
            series.exdates,
        )
        val moved = events[1]
        assertEquals(at("2026-09-21T08:00:00Z"), moved.recurrenceId)
        assertEquals(at("2026-09-21T12:00:00Z"), moved.start)
    }

    @Test
    fun `google - an email alarm is the server's and only the popup becomes a reminder`() {
        assertEquals(listOf(10), fixture("google.ics").events.first().reminderMinutes)
    }

    @Test
    fun `google - a floating time takes the calendar's X-WR-TIMEZONE`() {
        val dentist = fixture("google.ics").events.first { it.title == "Dentist" }
        assertEquals(at("2026-10-01T10:00:00Z"), dentist.start)
        assertEquals("Europe/Vienna", dentist.timezone)
    }

    @Test
    fun `google - a holiday keeps its transparency`() {
        val holiday = fixture("google.ics").events.first { it.title == "Nationalfeiertag" }
        assertTrue(holiday.allDay)
        assertEquals(EventAvailability.FREE, holiday.availability)
    }

    // ------------------------------------------------------------- Apple

    @Test
    fun `apple - audio alarms count, absolute and far-off triggers do not`() {
        val series = fixture("apple.ics").events.first { !it.isOverride }
        assertEquals(listOf(60), series.reminderMinutes)
    }

    @Test
    fun `apple - RDATE adds an occurrence and a DATE EXDATE removes the one that day`() {
        val series = fixture("apple.ics").events.first { !it.isOverride }
        assertEquals(at("2026-11-03T23:00:00Z"), series.start)
        assertEquals(listOf(at("2026-11-24T23:00:00Z")), series.rdates)
        assertEquals(listOf(at("2026-12-01T23:00:00Z")), series.exdates)
        assertEquals("Maple Street Library", series.location)
    }

    @Test
    fun `apple - a THISANDFUTURE override is marked as one`() {
        val override = fixture("apple.ics").events.single { it.isOverride }
        assertTrue(override.thisAndFuture)
        assertEquals(at("2027-01-05T23:00:00Z"), override.recurrenceId)
        assertEquals(at("2027-01-06T00:00:00Z"), override.start)
    }

    // ------------------------------------------------------------- inline cases

    private fun calendar(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") + "END:VCALENDAR\r\n"

    private fun vevent(vararg lines: String) =
        "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    @Test
    fun `a mozilla-prefixed TZID resolves to the zone at its end`() {
        val event = Ics.read(
            calendar(vevent("DTSTART;TZID=/mozilla.org/20050126_1/Europe/Berlin:20260115T090000")),
            elsewhere,
        ).single()
        assertEquals("Europe/Berlin", event.timezone)
        assertEquals(at("2026-01-15T08:00:00Z"), event.start)
    }

    @Test
    fun `a Windows TZID resolves without a VTIMEZONE block`() {
        val event = Ics.read(
            calendar(vevent("DTSTART;TZID=\"Eastern Standard Time\":20260115T090000")),
            elsewhere,
        ).single()
        assertEquals("America/New_York", event.timezone)
        assertEquals(at("2026-01-15T14:00:00Z"), event.start)
    }

    @Test
    fun `every Windows name maps to a zone java time knows`() {
        for ((windows, iana) in WINDOWS_ZONES) {
            assertTrue("$windows -> $iana", runCatching { ZoneId.of(iana) }.isSuccess)
        }
    }

    @Test
    fun `a VTIMEZONE with no daylight block is a fixed offset`() {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VTIMEZONE\r\nTZID:Office\r\nBEGIN:STANDARD\r\n" +
            "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0530\r\nTZOFFSETTO:+0530\r\nEND:STANDARD\r\n" +
            "END:VTIMEZONE\r\n" + vevent("DTSTART;TZID=Office:20260115T090000") + "END:VCALENDAR\r\n"
        val event = Ics.read(text, elsewhere).single()
        assertEquals(at("2026-01-15T03:30:00Z"), event.start)
    }

    @Test
    fun `a VEVENT with an unreadable DTSTART is counted as rejected`() {
        val document = Ics.readDocument(
            calendar(
                vevent("SUMMARY:Good", "DTSTART:20260115T090000Z"),
                vevent("SUMMARY:Bad", "DTSTART:2026-01-15 09:00"),
                vevent("SUMMARY:None"),
            ),
            elsewhere,
        )
        assertEquals(listOf("Good"), document.events.map { it.title })
        assertEquals(2, document.rejected)
    }

    @Test
    fun `a cancelled THISANDFUTURE occurrence ends the series before it`() {
        val events = Ics.read(
            calendar(
                vevent("UID:s", "DTSTART:20260105T090000Z", "RRULE:FREQ=WEEKLY;COUNT=10"),
                vevent(
                    "UID:s",
                    "RECURRENCE-ID;RANGE=THISANDFUTURE:20260126T090000Z",
                    "DTSTART:20260126T090000Z",
                    "STATUS:CANCELLED",
                ),
            ),
            elsewhere,
        )
        val series = events.single()
        assertEquals("FREQ=WEEKLY;UNTIL=20260126T085959Z", series.rrule)
    }

    @Test
    fun `a cancelled series takes its overrides with it`() {
        val events = Ics.read(
            calendar(
                vevent("UID:s", "DTSTART:20260105T090000Z", "RRULE:FREQ=WEEKLY", "STATUS:CANCELLED"),
                vevent("UID:s", "RECURRENCE-ID:20260112T090000Z", "DTSTART:20260112T100000Z"),
                vevent("UID:other", "DTSTART:20260105T090000Z"),
            ),
            elsewhere,
        )
        assertEquals(listOf("other"), events.map { it.uid })
    }

    @Test
    fun `a cancelled override with no series in the file is dropped`() {
        val events = Ics.read(
            calendar(
                vevent("UID:s", "RECURRENCE-ID:20260112T090000Z", "DTSTART:20260112T090000Z", "STATUS:CANCELLED"),
            ),
            elsewhere,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an alarm longer than four weeks is dropped rather than clamped`() {
        val event = Ics.read(
            calendar(
                "BEGIN:VEVENT\r\nDTSTART:20260115T090000Z\r\n" +
                    "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P4W\r\nEND:VALARM\r\n" +
                    "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P4WT1M\r\nEND:VALARM\r\n" +
                    "END:VEVENT\r\n",
            ),
            elsewhere,
        ).single()
        assertEquals(listOf(Ics.MAX_REMINDER_MINUTES), event.reminderMinutes)
    }

    @Test
    fun `an unknown CLASS or TRANSP leaves the field unset`() {
        val event = Ics.read(
            calendar(vevent("DTSTART:20260115T090000Z", "CLASS:X-SECRET", "TRANSP:MAYBE")),
            elsewhere,
        ).single()
        assertNull(event.access)
        assertNull(event.availability)
    }

    private fun assertNotNullAndGet(value: String?): String {
        assertNotNull(value)
        return value!!
    }

    // ------------------------------------------------------------- provider EXDATE column

    @Test
    fun `provider EXDATE values parse in every form sync adapters write`() {
        assertEquals(
            listOf(at("2026-10-01T08:00:00Z"), at("2026-10-08T08:00:00Z")),
            RecurrenceRules.parseProviderDates("20261001T080000Z,20261008T080000Z"),
        )
        assertEquals(
            listOf(at("2026-10-01T08:00:00Z")),
            RecurrenceRules.parseProviderDates("Europe/Vienna;20261001T100000"),
        )
        assertEquals(
            listOf(at("2026-10-01T00:00:00Z"), at("2026-10-02T08:00:00Z")),
            RecurrenceRules.parseProviderDates("20261001\n20261002T080000Z,garbage"),
        )
        assertTrue(RecurrenceRules.parseProviderDates(null).isEmpty())
    }
}
