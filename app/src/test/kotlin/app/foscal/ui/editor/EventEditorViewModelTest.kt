package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
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
import java.time.Instant

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

    private fun newEventVm(): EventEditorViewModel {
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "",
                "start" to "",
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, FakePreferences())
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
}
