package app.foscal.ui.week

import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.testPendingDeletes
import app.foscal.timedEvent
import app.foscal.ui.feedback.UserMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `paging inside the loaded window never goes back to the provider`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), UserMessages())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(1, repo.observedWindows.size)

        repeat(4) { vm.next(); advanceUntilIdle() }
        repeat(8) { vm.previous(); advanceUntilIdle() }

        assertEquals(1, repo.observedWindows.size)
    }

    @Test
    fun `paging off the end of the window loads a new one`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()))
        val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), UserMessages())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        repeat(20) { vm.next(); advanceUntilIdle() }

        assertTrue(repo.observedWindows.size > 1)
    }

    @Test
    fun `a week several pages ahead already has its events`() = runTest(dispatcher) {
        val monday = WeekViewModel.startOfWeek(today)
        val faraway = monday.plusWeeks(4)
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar()),
            events = listOf(timedEvent(1, at(faraway, 9), at(faraway, 10), title = "Later")),
        )
        val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), UserMessages())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        repeat(4) { vm.next(); advanceUntilIdle() }

        assertEquals(faraway, vm.state.value.anchor)
        assertEquals(listOf("Later"), vm.state.value.days[0].events.map { it.title })
        assertEquals(1, repo.observedWindows.size)
    }

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
            val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), UserMessages())
            backgroundScope.launch(dispatcher) { vm.state.collect {} }
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(monday, state.anchor)
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
        val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), UserMessages())
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
    fun `a refused move puts the block back and says so`() = runTest(dispatcher) {
        val monday = WeekViewModel.startOfWeek(today)
        val event = timedEvent(1, at(monday, 9), at(monday, 10), title = "Mon")
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar()), events = listOf(event))
        repo.refuseWrites = true
        val messages = UserMessages()
        val vm = WeekViewModel(repo, FakePreferences(), testPendingDeletes(), messages)
        advanceUntilIdle()

        vm.moveEvent(event, at(monday, 11).toEpochMilli(), at(monday, 12).toEpochMilli())
        advanceUntilIdle()

        assertEquals(1, vm.moveRefusals.value)
        assertEquals("Couldn't move the event", messages.messages.first())
    }

    @Test
    fun `navigation moves by the current span and back to today`() = runTest(dispatcher) {
        val vm = WeekViewModel(
            FakeCalendarRepository(calendars = listOf(testCalendar())),
            FakePreferences(),
            testPendingDeletes(),
            UserMessages(),
        )
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val monday = WeekViewModel.startOfWeek(today)
        vm.next(); advanceUntilIdle()
        assertEquals(monday.plusWeeks(1), vm.state.value.anchor)

        vm.goToToday(); advanceUntilIdle()
        assertEquals(monday, vm.state.value.anchor)
    }

    @Test
    fun `narrowing the span keeps the anchor and widening snaps back to Monday`() =
        runTest(dispatcher) {
            val vm = WeekViewModel(
                FakeCalendarRepository(calendars = listOf(testCalendar())),
                FakePreferences(),
                testPendingDeletes(),
                UserMessages(),
            )
            backgroundScope.launch(dispatcher) { vm.state.collect {} }
            advanceUntilIdle()

            val monday = WeekViewModel.startOfWeek(today)
            vm.setSpan(3); advanceUntilIdle()
            assertEquals(monday, vm.state.value.anchor)
            assertEquals(3, vm.state.value.days.size)

            // Page forward into mid-week, then widen: a week starting on a Thursday is not a week.
            vm.next(); advanceUntilIdle()
            assertEquals(monday.plusDays(3), vm.state.value.anchor)
            vm.setSpan(7); advanceUntilIdle()
            assertEquals(monday, vm.state.value.anchor)
            assertEquals(7, vm.state.value.days.size)

            vm.setSpan(1); advanceUntilIdle()
            assertEquals(1, vm.state.value.days.size)
        }

    @Test
    fun `the week starts on the day the preference names`() = runTest(dispatcher) {
        val prefs = FakePreferences(firstDay = DayOfWeek.SUNDAY)
        val vm = WeekViewModel(
            FakeCalendarRepository(calendars = listOf(testCalendar())),
            prefs,
            testPendingDeletes(),
            UserMessages(),
        )
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        assertEquals(DayOfWeek.SUNDAY, vm.state.value.anchor.dayOfWeek)

        // Changing it re-snaps the week already on screen rather than waiting for the user to page.
        prefs.firstDayOfWeek.value = DayOfWeek.WEDNESDAY
        advanceUntilIdle()
        assertEquals(DayOfWeek.WEDNESDAY, vm.state.value.anchor.dayOfWeek)

        // Day and 3 Days are anchored on a real date, so they must not be snapped to anything.
        vm.setSpan(3); advanceUntilIdle()
        val anchor = vm.state.value.anchor
        prefs.firstDayOfWeek.value = DayOfWeek.SUNDAY
        advanceUntilIdle()
        assertEquals(anchor, vm.state.value.anchor)
    }
}
