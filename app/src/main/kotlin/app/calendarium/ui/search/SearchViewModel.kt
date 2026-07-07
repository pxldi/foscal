package app.calendarium.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.core.model.Event
import app.calendarium.ui.util.Dates
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
) : ViewModel() {

    val zone: ZoneId = ZoneId.systemDefault()

    val query = MutableStateFlow("")
    private val window = MutableStateFlow(SearchWindow())
    private val today = Dates.todayFlow(zone)

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    val results: StateFlow<List<Event>> = combine(query, calendarIds, window, today) { q, ids, range, currentDate ->
        SearchRequest(q, ids, range, currentDate)
    }
        .debounce(250)
        .flatMapLatest { request ->
            flow {
                val from = request.today.minusYears(request.range.pastYears).atStartOfDay(zone).toInstant()
                val to = request.today.plusYears(request.range.futureYears).atStartOfDay(zone).toInstant()
                emit(repository.searchEvents(request.calendarIds, request.query, from, to))
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
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

private data class SearchRequest(
    val query: String,
    val calendarIds: Set<Long>,
    val range: SearchWindow,
    val today: LocalDate,
)
