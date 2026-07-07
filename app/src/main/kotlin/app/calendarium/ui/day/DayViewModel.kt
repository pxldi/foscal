package app.calendarium.ui.day

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.ui.common.TimelineDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DayUiState(
    val date: LocalDate,
    val day: TimelineDay = TimelineDay(date, emptyList()),
    val hasVisibleCalendars: Boolean = true,
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val _date = MutableStateFlow(LocalDate.now())

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    private val dayBounds = _date.map { d ->
        // Look back far enough that multi-day events which started days ago but are still running
        // get returned; pad the end for UTC all-day edge cases. We filter to [d] below.
        val from = d.minusDays(31).atStartOfDay(zone).toInstant()
        val to = d.plusDays(2).atStartOfDay(zone).toInstant()
        from to to
    }

    private val events = combine(calendarIds, dayBounds) { ids, range -> ids to range }
        .flatMapLatest { (ids, range) ->
            repository.observeEvents(ids, range.first, range.second)
        }

    val state: StateFlow<DayUiState> = combine(_date, events, calendarIds) { date, evts, ids ->
        val forDay = evts.filter { it.spansDay(date, zone) }.sortedBy { it.start }
        DayUiState(
            date = date,
            day = TimelineDay(date, forDay),
            hasVisibleCalendars = ids.isNotEmpty(),
            today = LocalDate.now(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DayUiState(date = LocalDate.now()),
    )

    fun nextDay() { _date.value = _date.value.plusDays(1) }
    fun previousDay() { _date.value = _date.value.minusDays(1) }
    fun goToToday() { _date.value = LocalDate.now() }
}
