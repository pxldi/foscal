package app.calendarium.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.core.model.Event
import app.calendarium.ui.util.Dates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
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

private data class AgendaBounds(
    val today: LocalDate,
    val range: AgendaWindow,
    val from: Instant,
    val to: Instant,
)

private const val AGENDA_PAGE_DAYS = 60L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val today = Dates.todayFlow(zone)
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

    private val bounds = combine(window, today) { range, currentDate ->
        // Read a little before the visible past window so long multi-day events already in
        // progress at the first visible day are included.
        val from = currentDate.minusDays(range.pastDays + 31).atStartOfDay(zone).toInstant()
        val to = currentDate.plusDays(range.futureDays + 1).atStartOfDay(zone).toInstant()
        AgendaBounds(currentDate, range, from, to)
    }

    private val events = combine(calendarIds, bounds) { ids, b -> ids to b }
        .flatMapLatest { (ids, b) ->
            repository.observeEvents(ids, b.from, b.to)
        }

    val state: StateFlow<AgendaUiState> = combine(calendarIds, events, window, today) { ids, evts, range, currentDate ->
        val firstVisible = currentDate.minusDays(range.pastDays)
        val lastVisible = currentDate.plusDays(range.futureDays)
        AgendaUiState(
            days = evts
                .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
                .groupBy({ it.first }, { it.second })
                .filterKeys { !it.isBefore(firstVisible) && !it.isAfter(lastVisible) }
                .toSortedMap()
                .map { (date, list) -> AgendaDay(date, list.sortedBy { it.start }) },
            hasVisibleCalendars = ids.isNotEmpty(),
            today = currentDate,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgendaUiState(today = LocalDate.now(zone)),
    )

    fun loadOlder() {
        window.value = window.value.copy(pastDays = window.value.pastDays + AGENDA_PAGE_DAYS)
    }

    fun loadNewer() {
        window.value = window.value.copy(futureDays = window.value.futureDays + AGENDA_PAGE_DAYS)
    }
}
