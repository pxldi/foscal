package app.foscal.ics

import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.model.Event
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
    fun `cancelled occurrences are re-applied to the imported series`() = runTest {
        val repository = FakeCalendarRepository()
        val text = Ics.write(series().toIcsEvents(emptyMap()))

        writeImported(repository, Ics.read(text, berlin), 9L, berlin)

        assertEquals(
            listOf(1L to Instant.parse("2026-07-27T09:00:00Z").toEpochMilli()),
            repository.instanceDeletes,
        )
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
        assertEquals(2L, repository.instanceDeletes.single().first)
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
}
