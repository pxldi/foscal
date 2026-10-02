package app.foscal.ui.quickadd

import app.foscal.R
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.ui.feedback.UserMessages
import app.foscal.ui.util.uiText
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

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
        vm.save(use24Hour = true, locale = Locale.ENGLISH)
        advanceUntilIdle()

        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `read-only calendars are not offered`() = runTest(dispatcher) {
        val readOnly = testCalendar().copy(id = 9, accessLevel = 200)
        val repo = FakeCalendarRepository(calendars = listOf(readOnly, testCalendar()))
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        assertEquals(listOf(testCalendar().id), vm.state.value.calendars.map { it.id })
        assertEquals(testCalendar().id, vm.state.value.selectedCalendarId)
    }

    @Test
    fun `unticked and unsynced calendars are not offered`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(
            calendars = listOf(
                testCalendar(id = 1, visible = false),
                testCalendar(id = 2, syncEnabled = false),
                testCalendar(id = 3),
            ),
        )
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        assertEquals(listOf(3L), vm.state.value.calendars.map { it.id })
    }

    @Test
    fun `the 12-hour clock saves a bare 3 30 in the afternoon`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        vm.updateQuery("Call 3:30")
        vm.save(use24Hour = false, locale = Locale.ENGLISH)
        advanceUntilIdle()

        val start = repo.created.single().start.atZone(java.time.ZoneId.systemDefault())
        assertEquals(java.time.LocalTime.of(15, 30), start.toLocalTime())
    }

    @Test
    fun `a refused quick add stays open with the text and says so`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        repo.refuseWrites = true
        val messages = UserMessages()
        val vm = QuickAddViewModel(repo, FakePreferences(), messages)
        advanceUntilIdle()

        vm.updateQuery("Dentist tomorrow 3pm")
        vm.save(use24Hour = true, locale = Locale.ENGLISH)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.finished)
        assertFalse(state.saving)
        assertEquals("Dentist tomorrow 3pm", state.query)
        assertEquals(uiText(R.string.message_add_failed), messages.messages.first())
    }

    @Test
    fun `a German range of hours saves its own end`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        vm.updateQuery("Workshop morgen von 14 bis 16 Uhr")
        vm.save(use24Hour = true, locale = Locale.GERMAN)
        advanceUntilIdle()

        val event = repo.created.single()
        val zone = ZoneId.systemDefault()
        assertEquals("Workshop", event.title)
        assertEquals(LocalTime.of(14, 0), event.start.atZone(zone).toLocalTime())
        assertEquals(LocalTime.of(16, 0), event.end.atZone(zone).toLocalTime())
    }

    @Test
    fun `a German range of days saves one all-day event spanning them`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = QuickAddViewModel(repo, FakePreferences(), UserMessages())
        advanceUntilIdle()

        vm.updateQuery("Urlaub 19.-23.10.2030")
        vm.save(use24Hour = true, locale = Locale.GERMAN)
        advanceUntilIdle()

        val event = repo.created.single()
        assertTrue(event.allDay)
        assertEquals(LocalDate.of(2030, 10, 19), event.start.atZone(ZoneOffset.UTC).toLocalDate())
        // Exclusive: midnight after the 23rd.
        assertEquals(LocalDate.of(2030, 10, 24), event.end.atZone(ZoneOffset.UTC).toLocalDate())
    }
}
