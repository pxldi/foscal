package app.foscal.ui.week

import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.timedEvent
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
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `startOfWeek snaps any day back to its Monday`() {
        val wednesday = LocalDate.of(2026, 7, 8) // a Wednesday
        assertEquals(LocalDate.of(2026, 7, 6), WeekViewModel.startOfWeek(wednesday))
        assertEquals(DayOfWeek.MONDAY, WeekViewModel.startOfWeek(wednesday).dayOfWeek)
    }

    @Test
    fun `state exposes seven days starting on the week's Monday with events bucketed by day`() =
        runTest(dispatcher) {
            val monday = WeekViewModel.startOfWeek(today)
            val repo = FakeCalendarRepository(
                calendars = listOf(testCalendar()),
                events = listOf(
                    timedEvent(1, at(monday, 9), at(monday, 10), title = "Mon"),
                    timedEvent(2, at(monday.plusDays(2), 9), at(monday.plusDays(2), 10), title = "Wed"),
                ),
            )
            val vm = WeekViewModel(repo, FakePreferences())
            backgroundScope.launch(dispatcher) { vm.state.collect {} }
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(monday, state.weekStart)
            assertEquals(7, state.days.size)
            assertEquals(listOf("Mon"), state.days[0].events.map { it.title })
            assertEquals(listOf("Wed"), state.days[2].events.map { it.title })
        }

    @Test
    fun `drag-to-move keeps every reminder and the event's timezone`() = runTest(dispatcher) {
        val monday = WeekViewModel.startOfWeek(today)
        val event = timedEvent(1, at(monday, 9), at(monday, 10), title = "Mon")
            .copy(timezone = "America/New_York")
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar()),
            events = listOf(event),
            reminderMinutes = listOf(30, 5),
        )
        val vm = WeekViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val newStart = at(monday, 11).toEpochMilli()
        vm.moveEvent(event, newStart, at(monday, 12).toEpochMilli())
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
        assertEquals(listOf(5, 30), repo.lastWritten?.reminderMinutes)
        assertEquals("America/New_York", repo.lastWritten?.timezone)
    }

    @Test
    fun `week navigation moves by whole weeks and back to this week`() = runTest(dispatcher) {
        val vm = WeekViewModel(FakeCalendarRepository(calendars = listOf(testCalendar())), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val monday = WeekViewModel.startOfWeek(today)
        vm.nextWeek(); advanceUntilIdle()
        assertEquals(monday.plusWeeks(1), vm.state.value.weekStart)

        vm.goToThisWeek(); advanceUntilIdle()
        assertEquals(monday, vm.state.value.weekStart)
    }
}
