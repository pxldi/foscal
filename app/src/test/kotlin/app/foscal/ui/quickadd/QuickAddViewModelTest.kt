package app.foscal.ui.quickadd

import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.ui.feedback.UserMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a saved quick add closes the screen`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        vm.updateQuery("Dentist tomorrow 3pm")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `a refused quick add stays open with the text and says so`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        repo.refuseWrites = true
        val messages = UserMessages()
        val vm = QuickAddViewModel(repo, FakePreferences(), messages)
        advanceUntilIdle()

        vm.updateQuery("Dentist tomorrow 3pm")
        vm.save()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.finished)
        assertFalse(state.saving)
        assertEquals("Dentist tomorrow 3pm", state.query)
        assertEquals("Couldn't add the event", messages.messages.first())
    }
}
