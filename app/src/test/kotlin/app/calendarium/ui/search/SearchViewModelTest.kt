package app.calendarium.ui.search

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today: LocalDate = LocalDate.now()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun repo(vararg hiddenCal: Long) = FakeCalendarRepository(
        calendars = listOf(testCalendar(id = 1), testCalendar(id = 2)),
        events = listOf(
            timedEvent(1, at(today, 9), at(today, 10), calendarId = 1, title = "Dentist appointment"),
            timedEvent(2, at(today, 11), at(today, 12), calendarId = 1, title = "Lunch"),
            timedEvent(3, at(today, 13), at(today, 14), calendarId = 2, title = "Dentist follow-up"),
        ),
    )

    @Test
    fun `query filters results by title after debounce`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("dentist")
        advanceUntilIdle()

        val titles = vm.results.value.map { it.title }
        assertEquals(2, titles.size)
        assertTrue(titles.all { it.contains("Dentist") })
    }

    @Test
    fun `results from a hidden calendar are excluded`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences(hidden = setOf("2")))
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("dentist")
        advanceUntilIdle()

        assertEquals(listOf("Dentist appointment"), vm.results.value.map { it.title })
    }
}
