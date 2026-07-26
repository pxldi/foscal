package app.foscal.ics

import app.foscal.core.model.Event
import app.foscal.core.model.Frequency
import app.foscal.core.model.IcsEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcsMappingTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun icsEvent(
        allDay: Boolean = false,
        timezone: String? = null,
        rrule: String? = null,
        reminders: List<Int> = emptyList(),
    ) = IcsEvent(
        title = "Standup",
        start = Instant.parse("2026-07-26T09:00:00Z"),
        end = Instant.parse("2026-07-26T09:30:00Z"),
        allDay = allDay,
        timezone = timezone,
        rrule = rrule,
        reminderMinutes = reminders,
    )

    @Test
    fun `an imported event keeps the zone it was authored in`() {
        val input = icsEvent(timezone = "America/New_York").toEventInput(7L, berlin)
        assertEquals("America/New_York", input.timezone)
        assertEquals(7L, input.calendarId)
    }

    @Test
    fun `a floating time falls back to the device zone`() {
        assertEquals("Europe/Berlin", icsEvent(timezone = null).toEventInput(1L, berlin).timezone)
    }

    @Test
    fun `all-day events are anchored to UTC regardless of the file's zone`() {
        val input = icsEvent(allDay = true, timezone = "America/New_York").toEventInput(1L, berlin)
        assertEquals("UTC", input.timezone)
    }

    @Test
    fun `a recurrence rule sets the frequency the provider keys off`() {
        // eventToContentValues only writes RRULE when frequency != NONE, so a rule that did not
        // set the frequency would be silently dropped on insert.
        val input = icsEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE").toEventInput(1L, berlin)
        assertEquals(Frequency.WEEKLY, input.frequency)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", input.rrule)
    }

    @Test
    fun `an unmodelled frequency imports as a single occurrence rather than being dropped`() {
        val input = icsEvent(rrule = "FREQ=HOURLY;INTERVAL=6").toEventInput(1L, berlin)
        assertEquals(Frequency.NONE, input.frequency)
    }

    @Test
    fun `a non-recurring event has no rule`() {
        val input = icsEvent().toEventInput(1L, berlin)
        assertEquals(Frequency.NONE, input.frequency)
        assertNull(input.rrule)
    }

    @Test
    fun `reminders carry across the import`() {
        assertEquals(
            listOf(10, 60),
            icsEvent(reminders = listOf(10, 60)).toEventInput(1L, berlin).reminderMinutes,
        )
    }

    @Test
    fun `an event with no reminders imports with none rather than the default`() {
        // EventInput.reminderMinutes defaults to listOf(15); an imported event must not silently
        // gain an alarm the file never asked for.
        assertEquals(emptyList<Int>(), icsEvent().toEventInput(1L, berlin).reminderMinutes)
    }

    @Test
    fun `exporting an event carries its rule, zone and reminders`() {
        val event = Event(
            id = 1L,
            calendarId = 2L,
            title = "Standup",
            location = "Room 3",
            description = "Daily",
            start = Instant.parse("2026-07-26T09:00:00Z"),
            end = Instant.parse("2026-07-26T09:30:00Z"),
            allDay = false,
            timezone = "Europe/Berlin",
            color = 0,
            rrule = "FREQ=DAILY",
        )
        val ics = event.toIcsEvent(listOf(15))
        assertEquals("Standup", ics.title)
        assertEquals("Room 3", ics.location)
        assertEquals("Europe/Berlin", ics.timezone)
        assertEquals("FREQ=DAILY", ics.rrule)
        assertEquals(listOf(15), ics.reminderMinutes)
    }

    @Test
    fun `an event round trips through the file format and back into an input`() {
        val original = icsEvent(timezone = "Europe/Berlin", rrule = "FREQ=DAILY", reminders = listOf(15))
        val text = app.foscal.core.model.Ics.write(listOf(original))
        val input = app.foscal.core.model.Ics.read(text, berlin).single().toEventInput(3L, berlin)

        assertEquals("Standup", input.title)
        assertEquals(original.start, input.start)
        assertEquals(original.end, input.end)
        assertEquals(Frequency.DAILY, input.frequency)
        assertEquals(listOf(15), input.reminderMinutes)
    }
}
