package app.foscal.ui.month

import app.foscal.allDayEvent
import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.timedEvent
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** The month grid's "skip short events" threshold. */
@OptIn(ExperimentalCoroutinesApi::class)
class MonthShortEventsTest {

    private val dispatcher = StandardTestDispatcher()
    private val day: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun repo() = FakeCalendarRepository(
        calendars = listOf(testCalendar()),
        events = listOf(
            timedEvent(id = 1, title = "Standup", start = at(day, 9, 0), end = at(day, 9, 15)),
            timedEvent(id = 2, title = "Review", start = at(day, 10, 0), end = at(day, 11, 0)),
            timedEvent(id = 3, title = "Workshop", start = at(day, 13, 0), end = at(day, 16, 0)),
            allDayEvent(id = 4, startDay = day, title = "Holiday"),
        ),
    )

    @Test
    fun `showing all keeps every event`() = runTest(dispatcher) {
        val vm = MonthViewModel(repo(), FakePreferences(monthMinimum = 0))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(
            setOf("Standup", "Review", "Workshop", "Holiday"),
            vm.state.value.eventsByDay.getValue(day).map { it.title }.toSet(),
        )
    }

    @Test
    fun `a half-hour threshold drops the fifteen-minute standup`() = runTest(dispatcher) {
        val vm = MonthViewModel(repo(), FakePreferences(monthMinimum = 30))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(
            setOf("Review", "Workshop", "Holiday"),
            vm.state.value.eventsByDay.getValue(day).map { it.title }.toSet(),
        )
    }

    // The boundary is inclusive: an event exactly as long as the threshold is not "under" it.
    @Test
    fun `an hour threshold keeps an event of exactly an hour`() = runTest(dispatcher) {
        val vm = MonthViewModel(repo(), FakePreferences(monthMinimum = 60))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(
            setOf("Review", "Workshop", "Holiday"),
            vm.state.value.eventsByDay.getValue(day).map { it.title }.toSet(),
        )
    }

    @Test
    fun `an all-day event survives every threshold`() = runTest(dispatcher) {
        val vm = MonthViewModel(repo(), FakePreferences(monthMinimum = 120))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(
            setOf("Workshop", "Holiday"),
            vm.state.value.eventsByDay.getValue(day).map { it.title }.toSet(),
        )
    }
}
