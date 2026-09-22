package app.foscal.ics

import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.model.Event
import app.foscal.core.model.EventAccess
import app.foscal.core.model.EventAvailability
import app.foscal.core.model.EventOverride
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.Ics
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how a recurring series and its per-occurrence exceptions cross the `.ics` boundary. */
class IcsTransferTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun event(
        id: Long,
        title: String = "Standup",
        start: String = "2026-07-26T09:00:00Z",
        end: String = "2026-07-26T09:30:00Z",
        rrule: String? = null,
    ) = Event(
        id = id,
        calendarId = 2L,
        title = title,
        location = null,
        description = null,
        start = Instant.parse(start),
        end = Instant.parse(end),
        allDay = false,
        timezone = "Europe/Berlin",
        color = 0,
        rrule = rrule,
    )

    private fun series() = ExportEvent(
        event = event(1L, rrule = "FREQ=DAILY"),
        overrides = listOf(
            EventOverride(
                originalInstanceTime = Instant.parse("2026-07-28T09:00:00Z").toEpochMilli(),
                originalAllDay = false,
                event = event(
                    id = 2L,
                    title = "Standup (late)",
                    start = "2026-07-28T11:00:00Z",
                    end = "2026-07-28T11:30:00Z",
                    // AOSP leaves this null, but a sync adapter may store the master's rule here.
                    rrule = "FREQ=DAILY",
                ),
            ),
        ),
        cancelledOccurrences = listOf(Instant.parse("2026-07-27T09:00:00Z").toEpochMilli()),
    )

    // ------------------------------------------------------------------ export

    @Test
    fun `a series exports as the master followed by its overrides`() {
        val events = series().toIcsEvents(emptyMap())
        assertEquals(listOf("Standup", "Standup (late)"), events.map { it.title })
        assertEquals(false, events[0].isOverride)
        assertTrue(events[1].isOverride)
    }

    @Test
    fun `an override carries the master's uid, not one derived from its own values`() {
        // The shared UID is the only thing tying an override to its series; deriving one per event
        // from the (edited) title and start would orphan it in every other calendar app.
        val events = series().toIcsEvents(emptyMap())
        assertEquals(events[0].uid, events[1].uid)
        assertTrue(events[0].uid!!.isNotBlank())
    }

    @Test
    fun `an override drops the rule the provider copied onto it`() {
        assertNull(series().toIcsEvents(emptyMap())[1].rrule)
    }

    @Test
    fun `cancelled occurrences ride on the master as exdates`() {
        val events = series().toIcsEvents(emptyMap())
        assertEquals(
            listOf(Instant.parse("2026-07-27T09:00:00Z")),
            events[0].exdates,
        )
        assertTrue(events[1].exdates.isEmpty())
    }

    @Test
    fun `reminders are looked up per row, so a master and its override keep their own`() {
        val events = series().toIcsEvents(mapOf(1L to listOf(15), 2L to listOf(60)))
        assertEquals(listOf(15), events[0].reminderMinutes)
        assertEquals(listOf(60), events[1].reminderMinutes)
    }

    @Test
    fun `a stored UID is exported as the series' UID, overrides included`() {
        val stored = series().copy(event = event(1L, rrule = "FREQ=DAILY").copy(uid = "abc@example.com"))
        val events = stored.toIcsEvents(emptyMap())
        assertEquals(listOf("abc@example.com", "abc@example.com"), events.map { it.uid })
    }

    // ------------------------------------------------------------------ import

    @Test
    fun `an exported series imports back as a master plus an exception`() = runTest {
        val repository = FakeCalendarRepository()
        val text = Ics.write(series().toIcsEvents(emptyMap()))

        val summary = writeImported(repository, Ics.read(text, berlin), 9L, berlin)

        assertEquals(2, summary.imported)
        assertEquals(0, summary.skipped)
        // Exactly one row is created: the series. The override is written against it.
        assertEquals(1, repository.created.size)
        assertEquals("FREQ=DAILY", repository.created.single().rrule)

        val (masterId, originalStart, input) = repository.instanceUpdates.single()
        assertEquals(1L, masterId)
        assertEquals(Instant.parse("2026-07-28T09:00:00Z").toEpochMilli(), originalStart)
        assertEquals("Standup (late)", input.title)
        assertNull(input.rrule)
    }

    @Test
    fun `cancelled occurrences go into the master's EXDATE column, not exception rows`() = runTest {
        val repository = FakeCalendarRepository()
        val text = Ics.write(series().toIcsEvents(emptyMap()))

        writeImported(repository, Ics.read(text, berlin), 9L, berlin)

        assertEquals(
            listOf(Instant.parse("2026-07-27T09:00:00Z")),
            repository.created.single().exdates,
        )
        assertTrue(repository.instanceDeletes.isEmpty())
    }

    @Test
    fun `an override is written against its own master, not whichever came first`() = runTest {
        val repository = FakeCalendarRepository()
        val first = ExportEvent(event(10L, title = "Other", rrule = "FREQ=WEEKLY"))
        val text = Ics.write(first.toIcsEvents(emptyMap()) + series().toIcsEvents(emptyMap()))

        writeImported(repository, Ics.read(text, berlin), 9L, berlin)

        assertEquals(listOf("Other", "Standup"), repository.created.map { it.title })
        // "Standup" was the second row created, so the override must address id 2.
        assertEquals(2L, repository.instanceUpdates.single().first)
    }

    @Test
    fun `an override whose master is missing is imported as a standalone event`() = runTest {
        val repository = FakeCalendarRepository()
        val orphan = series().toIcsEvents(emptyMap()).last()

        val summary = writeImported(repository, listOf(orphan), 9L, berlin)

        assertEquals(1, summary.imported)
        assertEquals("Standup (late)", repository.created.single().title)
        assertTrue(repository.instanceUpdates.isEmpty())
    }

    @Test
    fun `a file with no exceptions still imports every event`() = runTest {
        val repository = FakeCalendarRepository()
        val events = listOf(ExportEvent(event(1L)), ExportEvent(event(2L, title = "Review")))
        val text = Ics.write(events.flatMap { it.toIcsEvents(emptyMap()) })

        val summary = writeImported(repository, Ics.read(text, berlin), 9L, berlin)

        assertEquals(2, summary.imported)
        assertEquals(0, summary.skipped)
        assertTrue(repository.instanceUpdates.isEmpty())
        assertTrue(repository.instanceDeletes.isEmpty())
    }

    // ------------------------------------------------------------------ import: interop rules

    private fun ics(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") + "END:VCALENDAR\r\n"

    private fun vevent(vararg lines: String) =
        "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    @Test
    fun `on a synced calendar an override is excluded from its series and created on its own`() = runTest {
        val repository = FakeCalendarRepository()
        val text = Ics.write(series().toIcsEvents(emptyMap()))

        val summary = writeImported(repository, Ics.read(text, berlin), 9L, berlin, isLocal = false)

        assertEquals(2, summary.imported)
        assertTrue(repository.instanceUpdates.isEmpty())
        assertTrue(repository.instanceDeletes.isEmpty())
        val (master, moved) = repository.created
        assertEquals(
            listOf(Instant.parse("2026-07-27T09:00:00Z"), Instant.parse("2026-07-28T09:00:00Z")),
            master.exdates,
        )
        assertEquals("Standup (late)", moved.title)
        assertNull(moved.rrule)
        // Its own UID, so a sync adapter does not upload two events under the series' one.
        assertTrue(moved.uid != master.uid)
    }

    @Test
    fun `every created event carries its UID, synthetic when the file has none`() = runTest {
        val repository = FakeCalendarRepository()
        val events = Ics.read(
            ics(vevent("UID:given@example.com", "DTSTART:20260115T090000Z"), vevent("DTSTART:20260116T090000Z")),
            berlin,
        )

        writeImported(repository, events, 9L, berlin)

        assertEquals("given@example.com", repository.created[0].uid)
        assertEquals(Ics.syntheticUid(events[1]), repository.created[1].uid)
    }

    @Test
    fun `a series already on the calendar is skipped with its overrides`() = runTest {
        val repository = FakeCalendarRepository()
        val text = Ics.write(series().toIcsEvents(emptyMap()))
        val events = Ics.read(text, berlin)
        repository.existingUids += events.first().uid!!

        val summary = writeImported(repository, events, 9L, berlin)

        assertEquals(ImportSummary(imported = 0, skipped = 0, duplicates = 2), summary)
        assertTrue(repository.created.isEmpty())
        assertTrue(repository.instanceUpdates.isEmpty())
    }

    @Test
    fun `importing the same file twice adds nothing the second time`() = runTest {
        val repository = FakeCalendarRepository()
        val events = Ics.read(
            ics(
                vevent("UID:a", "DTSTART:20260115T090000Z", "RRULE:FREQ=WEEKLY", "RDATE:20260117T090000Z"),
                vevent("DTSTART:20260116T090000Z", "SUMMARY:No uid"),
                vevent("UID:orphan", "RECURRENCE-ID:20260120T090000Z", "DTSTART:20260120T100000Z"),
            ),
            berlin,
        )

        val first = writeImported(repository, events, 9L, berlin)
        repository.existingUids += repository.created.mapNotNull { it.uid }
        val createdBefore = repository.created.size
        val second = writeImported(repository, events, 9L, berlin)

        assertEquals(4, first.imported)
        assertEquals(0, second.imported)
        assertEquals(4, second.duplicates)
        assertEquals(createdBefore, repository.created.size)
    }

    @Test
    fun `an RDATE becomes a one-off copy of the series with its own UID`() = runTest {
        val repository = FakeCalendarRepository()
        val events = Ics.read(
            ics(vevent("UID:a", "DTSTART:20260115T090000Z", "DTEND:20260115T100000Z", "RRULE:FREQ=MONTHLY", "RDATE:20260120T090000Z")),
            berlin,
        )

        writeImported(repository, events, 9L, berlin)

        val copy = repository.created[1]
        assertEquals(Instant.parse("2026-01-20T09:00:00Z"), copy.start)
        assertEquals(Instant.parse("2026-01-20T10:00:00Z"), copy.end)
        assertNull(copy.rrule)
        assertTrue(copy.uid != "a")
    }

    @Test
    fun `a THISANDFUTURE override splits the series with its own exclusions`() = runTest {
        val repository = FakeCalendarRepository()
        val events = Ics.read(
            ics(
                vevent(
                    "UID:s",
                    "DTSTART:20260105T090000Z",
                    "RRULE:FREQ=WEEKLY;COUNT=10",
                    "EXDATE:20260112T090000Z,20260202T090000Z",
                ),
                vevent("UID:s", "RECURRENCE-ID;RANGE=THISANDFUTURE:20260126T090000Z", "DTSTART:20260126T100000Z"),
            ),
            berlin,
        )

        val summary = writeImported(repository, events, 9L, berlin)

        assertEquals(2, summary.imported)
        val (masterId, split, input) = repository.followingUpdates.single()
        assertEquals(1L, masterId)
        assertEquals(Instant.parse("2026-01-26T09:00:00Z").toEpochMilli(), split)
        assertEquals(true, repository.lastRebaseCount)
        assertEquals("FREQ=WEEKLY;COUNT=10", input.rrule)
        // The exclusion past the split moves with the series, an hour later.
        assertEquals(listOf(Instant.parse("2026-02-02T10:00:00Z")), input.exdates)
    }

    @Test
    fun `unreadable VEVENTs are reported as skipped`() = runTest {
        val repository = FakeCalendarRepository()
        val document = Ics.readDocument(ics(vevent("DTSTART:20260115T090000Z"), vevent("DTSTART:garbage")), berlin)

        val summary = writeImported(repository, document.events, 9L, berlin, rejected = document.rejected)

        assertEquals(ImportSummary(imported = 1, skipped = 1), summary)
    }

    @Test
    fun `CLASS and TRANSP reach the provider input`() = runTest {
        val repository = FakeCalendarRepository()
        val events = Ics.read(ics(vevent("DTSTART:20260115T090000Z", "CLASS:PRIVATE", "TRANSP:TRANSPARENT")), berlin)

        writeImported(repository, events, 9L, berlin)

        assertEquals(EventAccess.PRIVATE, repository.created.single().access)
        assertEquals(EventAvailability.FREE, repository.created.single().availability)
    }
}
