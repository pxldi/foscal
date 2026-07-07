package app.calendarium.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.core.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AgendaDay(
    val date: LocalDate,
    val events: List<Event>,
)

data class AgendaUiState(
    val days: List<AgendaDay> = emptyList(),
    val hasVisibleCalendars: Boolean = true,
    val today: LocalDate = LocalDate.now(),
)

private data class AgendaWindow(val pastDays: Long = 60L, val futureDays: Long = 60L)

private const val AGENDA_PAGE_DAYS = 60L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val today = LocalDate.now()
    private val window = MutableStateFlow(AgendaWindow())

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    private val bounds = window.map { range ->
        // Read a little before the visible past window so long multi-day events already in
        // progress at the first visible day are included.
        val from = today.minusDays(range.pastDays + 31).atStartOfDay(zone).toInstant()
        val to = today.plusDays(range.futureDays + 1).atStartOfDay(zone).toInstant()
        Triple(range, from, to)
    }

    private val events = combine(calendarIds, bounds) { ids, b -> ids to b }
        .flatMapLatest { (ids, b) ->
            repository.observeEvents(ids, b.second, b.third)
        }

    val state: StateFlow<AgendaUiState> = combine(calendarIds, events, window) { ids, evts, range ->
        val firstVisible = today.minusDays(range.pastDays)
        val lastVisible = today.plusDays(range.futureDays)
        AgendaUiState(
            days = evts
                .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
                .groupBy({ it.first }, { it.second })
                .filterKeys { !it.isBefore(firstVisible) && !it.isAfter(lastVisible) }
                .toSortedMap()
                .map { (date, list) -> AgendaDay(date, list.sortedBy { it.start }) },
            hasVisibleCalendars = ids.isNotEmpty(),
            today = today,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgendaUiState(),
    )

    fun loadOlder() {
        window.value = window.value.copy(pastDays = window.value.pastDays + AGENDA_PAGE_DAYS)
    }

    fun loadNewer() {
        window.value = window.value.copy(futureDays = window.value.futureDays + AGENDA_PAGE_DAYS)
    }
}
