package app.calendarium.ui.agenda

import app.calendarium.allDayEvent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `groups events by day, sorts within a day, and drops past days`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar()),
            events = listOf(
                timedEvent(1, at(today, 14), at(today, 15), title = "Afternoon"),
                timedEvent(2, at(today, 9), at(today, 10), title = "Morning"),
                timedEvent(3, at(today.minusDays(2), 9), at(today.minusDays(2), 10), title = "Past"),
                timedEvent(4, at(today.plusDays(1), 9), at(today.plusDays(1), 10), title = "Tomorrow"),
            ),
        )
        val vm = AgendaViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(today, today.plusDays(1)), state.days.map { it.date })
        assertEquals(listOf("Morning", "Afternoon"), state.days.first().events.map { it.title })
        assertTrue(state.days.none { it.date.isBefore(today) })
    }

    @Test
    fun `multi-day event appears on every spanned day from today onward`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar()),
            events = listOf(allDayEvent(1, startDay = today, days = 3, title = "Trip")),
        )
        val vm = AgendaViewModel(repo, FakePreferences())
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(
            listOf(today, today.plusDays(1), today.plusDays(2)),
            state.days.map { it.date },
        )
        assertTrue(state.days.all { it.events.single().title == "Trip" })
    }

    @Test
    fun `hasVisibleCalendars is false when the only calendar is hidden`() = runTest(dispatcher) {
        val repo = FakeCalendarRepository(calendars = listOf(testCalendar(id = 7)))
        val vm = AgendaViewModel(repo, FakePreferences(hidden = setOf("7")))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()

        assertFalse(vm.state.value.hasVisibleCalendars)
    }
}
