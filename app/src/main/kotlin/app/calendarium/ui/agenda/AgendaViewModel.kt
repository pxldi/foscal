package app.calendarium.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

private const val AGENDA_HORIZON_DAYS = 60L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val window: Pair<Instant, Instant> = run {
        val today = LocalDate.now()
        today.atStartOfDay(zone).toInstant() to
            today.plusDays(AGENDA_HORIZON_DAYS).atStartOfDay(zone).toInstant()
    }

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    private val events = calendarIds.flatMapLatest { ids ->
        repository.observeEvents(ids, window.first, window.second)
    }

    val state: StateFlow<AgendaUiState> = combine(calendarIds, events) { ids, evts ->
        AgendaUiState(
            days = evts.groupBy { it.start.atZone(zone).toLocalDate() }
                .toSortedMap()
                .map { (date, list) -> AgendaDay(date, list.sortedBy { it.start }) },
            hasVisibleCalendars = ids.isNotEmpty(),
            today = LocalDate.now(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgendaUiState(),
    )
}
