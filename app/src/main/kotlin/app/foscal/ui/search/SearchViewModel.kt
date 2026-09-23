package app.foscal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Event
import app.foscal.ui.feedback.PendingDeletes
import app.foscal.ui.feedback.withoutPendingDeletes
import app.foscal.ui.util.Dates
import app.foscal.ui.util.visibleCalendarIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private data class SearchWindow(val pastYears: Long = 2L, val futureYears: Long = 2L)

private const val SEARCH_PAGE_YEARS = 2L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
    pendingDeletes: PendingDeletes,
) : ViewModel() {

    val zone: ZoneId = ZoneId.systemDefault()

    val query = MutableStateFlow("")
    private val window = MutableStateFlow(SearchWindow())
    private val today = Dates.todayFlow(zone)

    private val calendarIds = visibleCalendarIds(repository, prefs)

    val results: StateFlow<SearchResults> = combine(query, calendarIds, window, today) { q, ids, range, currentDate ->
        SearchRequest(q, ids, range, currentDate)
    }
        .debounce(250)
        .flatMapLatest { request ->
            flow {
                val from = request.today.minusYears(request.range.pastYears).atStartOfDay(zone).toInstant()
                val to = request.today.plusYears(request.range.futureYears).atStartOfDay(zone).toInstant()
                emit(repository.searchEvents(request.calendarIds, request.query, from, to))
            }
                // A result deleted from its detail screen is still in the provider during the undo
                // window, and this list is what the user comes back to.
                .withoutPendingDeletes(pendingDeletes)
                .map { SearchResults(answers = request.query, events = it) }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SearchResults(),
        )

    fun onQueryChange(value: String) {
        query.value = value
        window.value = SearchWindow()
    }

    fun loadOlder() {
        window.value = window.value.copy(pastYears = window.value.pastYears + SEARCH_PAGE_YEARS)
    }

    fun loadNewer() {
        window.value = window.value.copy(futureYears = window.value.futureYears + SEARCH_PAGE_YEARS)
    }
}

/**
 * The events found for the query in [answers], or no answer yet when that is null.
 *
 * The query travels with its results so the screen can tell "nothing found" from "not searched
 * yet". Without it, every keystroke showed "No matching events." for the debounce and the read.
 */
data class SearchResults(
    val answers: String? = null,
    val events: List<Event> = emptyList(),
) {
    /** Whether these results are the finished search for [query]. */
    fun isFor(query: String): Boolean = answers == query
}

private data class SearchRequest(
    val query: String,
    val calendarIds: Set<Long>,
    val range: SearchWindow,
    val today: LocalDate,
)
