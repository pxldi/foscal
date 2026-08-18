package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val start = Instant.parse("2026-07-09T09:00:00Z")
    private val calendar = Calendar(
        id = 1,
        displayName = "Local",
        accountName = "Foscal",
        accountType = "LOCAL",
        ownerName = null,
        color = 0xFF1976D2.toInt(),
        visible = true,
        syncEnabled = true,
    )
    private val recurring = Event(
        id = 10,
        calendarId = 1,
        title = "Standup",
        location = null,
        description = null,
        start = start,
        end = start.plusSeconds(3_600L),
        allDay = false,
        timezone = "UTC",
        color = 0xFF1976D2.toInt(),
        rrule = "FREQ=WEEKLY",
    )

    private lateinit var repo: FakeCalendarRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring),
            reminderMinutes = listOf(15),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recurringEditVm(): EventEditorViewModel {
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "10",
                "start" to start.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, FakePreferences())
    }

    private fun newEventVm(prefs: FakePreferences = FakePreferences()): EventEditorViewModel {
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "",
                "start" to "",
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, prefs)
    }

    @Test
    fun `a default reminder of None leaves a new event with no reminders`() = runTest(dispatcher) {
        val vm = newEventVm(FakePreferences(defaultReminder = null))
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), repo.lastCreated?.reminderMinutes)
    }

    @Test
    fun `moving the end before the start drags the start back with it`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        val startDate = vm.state.value.startDate

        vm.updateEndDate(startDate.minusDays(3))

        val state = vm.state.value
        assertEquals(startDate.minusDays(3), state.startDate)
        assertEquals(startDate.minusDays(3), state.endDate)
    }

    @Test
    fun `moving the start past the end drags the end forward with it`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        val endDate = vm.state.value.endDate

        vm.updateStartDate(endDate.plusDays(5))

        val state = vm.state.value
        assertEquals(endDate.plusDays(5), state.startDate)
        assertEquals(endDate.plusDays(5), state.endDate)
    }

    @Test
    fun `an end time earlier in the same day never survives as an inverted span`() =
        runTest(dispatcher) {
            val vm = newEventVm()
            advanceUntilIdle()
            val today = vm.state.value.startDate
            vm.updateStartDate(today)
            vm.updateEndDate(today)
            vm.updateStartTime(java.time.LocalTime.of(14, 0))

            vm.updateEndTime(java.time.LocalTime.of(9, 0))

            val state = vm.state.value
            val startAt = state.startDate.atTime(state.startTime)
            val endAt = state.endDate.atTime(state.endTime)
            assertEquals(false, endAt.isBefore(startAt))
        }

    @Test
    fun `saving a new event creates it`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.CREATE, repo.lastOp)
    }

    @Test
    fun `an event years outside the old scan window still opens for editing`() = runTest(dispatcher) {
        // The lookup used to scan a +-2-year window of instances, so anything beyond it silently
        // fell through to the blank "new event" form and saving created a duplicate.
        val farOff = Instant.parse("2031-03-04T08:00:00Z")
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(id = 42, title = "Passport", start = farOff, end = farOff.plusSeconds(3_600L), rrule = null)),
        )
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "42",
                "start" to farOff.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        val vm = EventEditorViewModel(handle, repo, FakePreferences())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(true, state.isEditing)
        assertEquals("Passport", state.title)
        assertEquals(42L, state.eventId)
    }

    @Test
    fun `save on recurring event prompts for scope without writing`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Standup renamed")
        vm.save()
        advanceUntilIdle()

        // Picking a scope is required; nothing should be written yet.
        assertEquals(RecurrenceScopePrompt.SAVE, vm.state.value.scopePrompt)
        assertNull(repo.lastOp)
    }

    @Test
    fun `resolveScope SINGLE routes to updateEventInstance`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.SINGLE)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE_INSTANCE, repo.lastOp)
    }

    @Test
    fun `resolveScope THIS_AND_FOLLOWING routes to updateEventFollowing`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.THIS_AND_FOLLOWING)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE_FOLLOWING, repo.lastOp)
        // Recurrence was not touched in the editor, so the count should be rebased (preserved).
        assertEquals(true, repo.lastRebaseCount)
    }

    @Test
    fun `resolveScope ALL_EVENTS routes to updateEvent`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.ALL_EVENTS)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
    }

    @Test
    fun `new event loads recent locations newest-first and deduped`() = runTest(dispatcher) {
        fun located(id: Long, location: String?, at: Instant) = recurring.copy(
            id = id,
            location = location,
            start = at,
            end = at.plusSeconds(3_600L),
            rrule = null,
        )
        // Two events share "Room 3B"; the blank one is ignored. Newest start wins for ordering.
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(
                located(1, "Room 3B", start),
                located(2, "Cafeteria", start.plusSeconds(86_400L)),
                located(3, "Room 3B", start.plusSeconds(172_800L)),
                located(4, "  ", start.plusSeconds(259_200L)),
            ),
        )
        val vm = newEventVm()
        advanceUntilIdle()

        assertEquals(listOf("Room 3B", "Cafeteria"), vm.state.value.recentLocations)
    }

    @Test
    fun `editing an event preserves every reminder it already had`() = runTest(dispatcher) {
        // An event synced from CalDAV with three alarms. Renaming it must not drop two of them:
        // updateEvent rewrites the whole reminder set, so the input has to carry all of them.
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(rrule = null)),
            reminderMinutes = listOf(60, 10, 1440),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        assertEquals(listOf(10, 60, 1440), vm.state.value.reminderMinutes)

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
        assertEquals(listOf(10, 60, 1440), repo.lastWritten?.reminderMinutes)
    }

    @Test
    fun `toggleReminder adds removes and clears`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        assertEquals(listOf(15), vm.state.value.reminderMinutes)

        vm.toggleReminder(60)
        assertEquals(listOf(15, 60), vm.state.value.reminderMinutes)

        vm.toggleReminder(15)
        assertEquals(listOf(60), vm.state.value.reminderMinutes)

        vm.toggleReminder(null)
        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)

        vm.updateTitle("No alarms")
        vm.save()
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), repo.lastWritten?.reminderMinutes)
    }

    @Test
    fun `editing keeps the event's original timezone instead of the device zone`() =
        runTest(dispatcher) {
            // Authored in New York; edited on a device set to some other zone. Re-anchoring it to
            // the device zone would shift the event for every other client on the calendar.
            repo = FakeCalendarRepository(
                calendars = listOf(calendar),
                events = listOf(recurring.copy(rrule = null, timezone = "America/New_York")),
            )
            val vm = recurringEditVm()
            advanceUntilIdle()

            vm.updateTitle("Renamed")
            vm.save()
            advanceUntilIdle()

            assertEquals("America/New_York", repo.lastWritten?.timezone)
        }

    @Test
    fun `an unparseable stored timezone falls back to the device zone`() = runTest(dispatcher) {
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(rrule = null, timezone = "Not/AZone")),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals(ZoneId.systemDefault().id, repo.lastWritten?.timezone)
    }

    @Test
    fun `an all-day event is stored in UTC regardless of its original zone`() = runTest(dispatcher) {
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(
                recurring.copy(rrule = null, allDay = true, timezone = "America/New_York"),
            ),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals(ZoneOffset.UTC.id, repo.lastWritten?.timezone)
    }

    @Test
    fun `a new event is authored in the device zone`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(ZoneId.systemDefault().id, repo.lastWritten?.timezone)
    }

    @Test
    fun `delete SINGLE vs FOLLOWING vs ALL route correctly`() = runTest(dispatcher) {
        // SINGLE
        val a = recurringEditVm()
        advanceUntilIdle()
        a.delete()
        advanceUntilIdle()
        a.resolveScope(RecurrenceScope.SINGLE)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_INSTANCE, repo.lastOp)

        // FOLLOWING
        repo.reset()
        val b = recurringEditVm()
        advanceUntilIdle()
        b.delete()
        advanceUntilIdle()
        b.resolveScope(RecurrenceScope.THIS_AND_FOLLOWING)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_FOLLOWING, repo.lastOp)

        // ALL
        repo.reset()
        val c = recurringEditVm()
        advanceUntilIdle()
        c.delete()
        advanceUntilIdle()
        c.resolveScope(RecurrenceScope.ALL_EVENTS)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE, repo.lastOp)
    }

    @Test
    fun `a one-day all-day event survives an edit without growing a day`() = runTest(dispatcher) {
        // The provider stores an all-day END as exclusive UTC midnight of the day after the last
        // covered day, so this one-day event on the 19th is stored as 19th -> 20th.
        val day = LocalDate.of(2026, 8, 19)
        val allDay = Event(
            id = 20,
            calendarId = 1,
            title = "Holiday",
            location = null,
            description = null,
            start = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
            end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            allDay = true,
            timezone = "UTC",
            color = 0xFF1976D2.toInt(),
        )
        repo = FakeCalendarRepository(calendars = listOf(calendar), events = listOf(allDay))
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "20",
                "start" to allDay.start.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        val vm = EventEditorViewModel(handle, repo, FakePreferences())
        advanceUntilIdle()

        // The editor shows the last covered day, not the exclusive end.
        assertEquals(day, vm.state.value.startDate)
        assertEquals(day, vm.state.value.endDate)

        // And saving it straight back writes the same instants it read, rather than pushing the
        // end out by a day on every visit to the editor.
        vm.save()
        advanceUntilIdle()

        assertEquals(allDay.start, repo.lastWritten?.start)
        assertEquals(allDay.end, repo.lastWritten?.end)
    }
}
