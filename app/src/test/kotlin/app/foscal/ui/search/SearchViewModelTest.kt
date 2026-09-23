package app.foscal.ui.search

import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import app.foscal.testPendingDeletes
import app.foscal.timedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
        val vm = SearchViewModel(repo(), FakePreferences(), testPendingDeletes())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("dentist")
        advanceUntilIdle()

        val titles = vm.results.value.events.map { it.title }
        assertEquals(2, titles.size)
        assertTrue(titles.all { it.contains("Dentist") })
    }

    @Test
    fun `results name the query they answer only once the search has run`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences(), testPendingDeletes())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("dentist")
        advanceUntilIdle()
        vm.onQueryChange("dentistry")
        advanceTimeBy(100)

        // The previous answer is still there, but it is not an answer to what is typed now.
        assertFalse(vm.results.value.isFor("dentistry"))
        assertEquals(2, vm.results.value.events.size)

        advanceUntilIdle()
        assertTrue(vm.results.value.isFor("dentistry"))
        assertTrue(vm.results.value.events.isEmpty())
    }

    @Test
    fun `nothing is answered before the first search completes`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences(), testPendingDeletes())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("zzz")
        assertFalse(vm.results.value.isFor("zzz"))

        advanceUntilIdle()
        assertTrue(vm.results.value.isFor("zzz"))
        assertTrue(vm.results.value.events.isEmpty())
    }

    @Test
    fun `loading more of the same query keeps it answered`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences(), testPendingDeletes())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }
        vm.onQueryChange("dentist")
        advanceUntilIdle()

        vm.loadOlder()
        advanceTimeBy(100)

        assertTrue(vm.results.value.isFor("dentist"))
        assertEquals(2, vm.results.value.events.size)
    }

    @Test
    fun `results from a hidden calendar are excluded`() = runTest(dispatcher) {
        val vm = SearchViewModel(repo(), FakePreferences(hidden = setOf("2")), testPendingDeletes())
        backgroundScope.launch(dispatcher) { vm.results.collect {} }

        vm.onQueryChange("dentist")
        advanceUntilIdle()

        assertEquals(listOf("Dentist appointment"), vm.results.value.events.map { it.title })
    }
}
