package app.calendarium.ui.day

import app.calendarium.at
import app.calendarium.core.data.FakeCalendarRepository
import app.calendarium.core.data.FakePreferences
import app.calendarium.testCalendar
import app.calendarium.timedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun repo() = FakeCalendarRepository(
        calendars = listOf(testCalendar()),
        events = listOf(
            timedEvent(1, at(today, 9), at(today, 10), title = "Today"),
            timedEvent(2, at(today.plusDays(1), 9), at(today.plusDays(1), 10), title = "Tomorrow"),
        ),
    )

    @Test
    fun `default day shows only events spanning today`() = runTest(dispatcher) {
        val vm = DayViewModel(repo(), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(today, state.date)
        assertEquals(listOf("Today"), state.day.events.map { it.title })
    }

    @Test
    fun `nextDay advances the date and re-filters events`() = runTest(dispatcher) {
        val vm = DayViewModel(repo(), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        vm.nextDay()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(today.plusDays(1), state.date)
        assertEquals(listOf("Tomorrow"), state.day.events.map { it.title })
    }

    @Test
    fun `previousDay then goToToday returns to today`() = runTest(dispatcher) {
        val vm = DayViewModel(repo(), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        vm.previousDay()
        advanceUntilIdle()
        assertEquals(today.minusDays(1), vm.state.value.date)

        vm.goToToday()
        advanceUntilIdle()
        assertEquals(today, vm.state.value.date)
    }

    @Test
    fun `hasVisibleCalendars is false when the calendar is hidden`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar(id = 3)))
        val vm = DayViewModel(repo, FakePreferences(hidden = setOf("3")))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        assertFalse(vm.state.value.hasVisibleCalendars)
    }
}
