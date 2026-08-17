package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.core.model.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Per-calendar reminder defaults, from the editor's point of view.
 *
 * The rules that matter here are the ones a user would notice: picking the work calendar has to
 * bring the work calendar's reminder with it, and it must stop doing that the moment they set a
 * reminder themselves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorReminderDefaultsTest {

    private val dispatcher = StandardTestDispatcher()

    private fun calendar(id: Long, name: String) = Calendar(
        id = id,
        displayName = name,
        accountName = "Foscal",
        accountType = "LOCAL",
        ownerName = null,
        color = 0xFF1976D2.toInt(),
        visible = true,
        syncEnabled = true,
    )

    private val work = calendar(1, "Work")
    private val birthdays = calendar(2, "Birthdays")

    private lateinit var repo: FakeCalendarRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeCalendarRepository(calendars = listOf(work, birthdays))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newEventVm(prefs: FakePreferences) = EventEditorViewModel(
        SavedStateHandle(
            mapOf("eventId" to "", "start" to "", "calendarId" to "", "end" to ""),
        ),
        repo,
        prefs,
    )

    @Test
    fun `a new event uses the selected calendar's override`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(1L to 30)),
        )
        advanceUntilIdle()

        assertEquals(listOf(30), vm.state.value.reminderMinutes)
    }

    @Test
    fun `a calendar with no override follows the global default`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(2L to 1440)),
        )
        advanceUntilIdle()

        // The first visible calendar (id 1) is selected, and it has no override of its own.
        assertEquals(listOf(15), vm.state.value.reminderMinutes)
    }

    /** "None on this calendar" has to beat a non-null global default, not fall back to it. */
    @Test
    fun `an override of None leaves a new event with no reminder`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(1L to null)),
        )
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)
    }

    @Test
    fun `switching calendars re-applies the new calendar's default`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(
                defaultReminder = 15,
                calendarReminders = mapOf(1L to 30, 2L to null),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(30), vm.state.value.reminderMinutes)

        vm.selectCalendar(2)
        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)

        vm.selectCalendar(1)
        assertEquals(listOf(30), vm.state.value.reminderMinutes)
    }

    /**
     * The convenience must never overwrite a decision. A user who sets a reminder and *then*
     * corrects the calendar would otherwise silently lose it.
     */
    @Test
    fun `switching calendars leaves a reminder the user chose alone`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(2L to 1440)),
        )
        advanceUntilIdle()

        vm.toggleReminder(5)
        assertEquals(listOf(5, 15), vm.state.value.reminderMinutes)

        vm.selectCalendar(2)
        assertEquals(listOf(5, 15), vm.state.value.reminderMinutes)
    }

    /** Clearing to "None" is a choice too, and switching calendars must not undo it. */
    @Test
    fun `switching calendars leaves an explicit None alone`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(2L to 1440)),
        )
        advanceUntilIdle()

        vm.toggleReminder(null)
        vm.selectCalendar(2)

        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)
    }

    @Test
    fun `the resolved default is what gets saved`() = runTest(dispatcher) {
        val vm = newEventVm(
            FakePreferences(defaultReminder = 15, calendarReminders = mapOf(1L to 30)),
        )
        advanceUntilIdle()

        vm.updateTitle("Standup")
        vm.save()
        advanceUntilIdle()

        assertEquals(listOf(30), repo.lastCreated?.reminderMinutes)
    }
}
