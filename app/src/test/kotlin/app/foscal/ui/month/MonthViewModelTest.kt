package app.foscal.ui.month

import app.foscal.allDayEvent
import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.timedEvent
import app.foscal.ui.common.SwipeDirection
import app.foscal.ui.common.swipeDirection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `eventsByDay groups a multi-day event onto each covered day`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar()),
            events = listOf(
                allDayEvent(1, startDay = today, days = 2, title = "Trip"),
                timedEvent(2, at(today, 9), at(today, 10), title = "Standup"),
            ),
        )
        val vm = MonthViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val byDay = vm.state.value.eventsByDay
        assertEquals(setOf("Trip", "Standup"), byDay.getValue(today).map { it.title }.toSet())
        assertEquals(listOf("Trip"), byDay.getValue(today.plusDays(1)).map { it.title })
    }

    @Test
    fun `navigation moves the visible month and back to a chosen month`() = runTest(dispatcher) {
        val vm = MonthViewModel(FakeCalendarRepository(calendars = listOf(testCalendar())), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val start = YearMonth.now()
        vm.nextMonth(); advanceUntilIdle()
        assertEquals(start.plusMonths(1), vm.state.value.visibleMonth)

        vm.previousMonth(); vm.previousMonth(); advanceUntilIdle()
        assertEquals(start.minusMonths(1), vm.state.value.visibleMonth)

        vm.goToMonth(YearMonth.of(2030, 1)); advanceUntilIdle()
        assertEquals(YearMonth.of(2030, 1), vm.state.value.visibleMonth)
    }

    @Test
    fun `selectDate updates the selection`() = runTest(dispatcher) {
        val vm = MonthViewModel(FakeCalendarRepository(calendars = listOf(testCalendar())), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        vm.selectDate(today.plusDays(5)); advanceUntilIdle()
        assertEquals(today.plusDays(5), vm.state.value.selectedDate)

        vm.selectDate(null); advanceUntilIdle()
        assertTrue(vm.state.value.selectedDate == null)
    }

    @Test
    fun `paging inside the loaded window never goes back to the provider`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = MonthViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(1, repo.observedWindows.size)

        vm.nextMonth(); advanceUntilIdle()
        vm.previousMonth(); vm.previousMonth(); advanceUntilIdle()

        assertEquals(1, repo.observedWindows.size)
    }

    @Test
    fun `paging off the end of the window loads a new one`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = MonthViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        repeat(6) { vm.nextMonth(); advanceUntilIdle() }

        assertTrue(repo.observedWindows.size > 1)
    }

    @Test
    fun `visible month cells span only the weeks the month occupies`() {
        val july = visibleMonthCells(YearMonth.of(2026, 7), firstDayOfWeek = DayOfWeek.MONDAY)
        val august = visibleMonthCells(YearMonth.of(2026, 8), firstDayOfWeek = DayOfWeek.MONDAY)

        // July 2026 starts on a Wednesday and fits in five weeks.
        assertEquals(35, july.size)
        assertEquals(LocalDate.of(2026, 6, 29), july.first())
        assertEquals(LocalDate.of(2026, 8, 2), july.last())
        // August 2026 starts on a Saturday and needs six weeks.
        assertEquals(42, august.size)
        assertEquals(LocalDate.of(2026, 7, 27), august.first())
        assertEquals(LocalDate.of(2026, 9, 6), august.last())
    }

    @Test
    fun `swipe navigates left and right`() {
        val threshold = 56f

        assertEquals(SwipeDirection.Next, swipeDirection(-80f, threshold))
        assertEquals(SwipeDirection.Previous, swipeDirection(80f, threshold))
    }

    @Test
    fun `swipe ignores drags that have not travelled far enough`() {
        val threshold = 56f

        assertTrue(swipeDirection(0f, threshold) == null)
        assertTrue(swipeDirection(-55f, threshold) == null)
        assertTrue(swipeDirection(55f, threshold) == null)
        // Exactly on the threshold counts, so a drag cannot stall one pixel short of committing.
        assertEquals(SwipeDirection.Next, swipeDirection(-56f, threshold))
        assertEquals(SwipeDirection.Previous, swipeDirection(56f, threshold))
    }
}
