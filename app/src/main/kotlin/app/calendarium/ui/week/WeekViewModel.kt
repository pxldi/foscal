package app.calendarium.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.core.model.Event
import app.calendarium.ui.common.TimelineDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class WeekUiState(
    val weekStart: LocalDate,
    val days: List<TimelineDay> = emptyList(),
    val hasVisibleCalendars: Boolean = true,
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeekViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val _weekStart = MutableStateFlow(startOfWeek(LocalDate.now()))

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    private val weekBounds = _weekStart.map { start ->
        // Look back far enough that multi-day events which started before this week but are
        // still running get returned by the provider; pad the end for UTC all-day edge cases.
        val from = start.minusDays(31).atStartOfDay(zone).toInstant()
        val to = start.plusDays(8).atStartOfDay(zone).toInstant()
        from to to
    }

    private val events = combine(calendarIds, weekBounds) { ids, range -> ids to range }
        .flatMapLatest { (ids, range) ->
            repository.observeEvents(ids, range.first, range.second)
        }

    val state: StateFlow<WeekUiState> = combine(
        _weekStart,
        events,
        calendarIds,
    ) { start, evts, ids ->
        val byDate = evts
            .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
            .groupBy({ it.first }, { it.second })
        val days = (0 until 7).map { offset ->
            val date = start.plusDays(offset.toLong())
            TimelineDay(date, byDate[date].orEmpty().sortedBy { it.start })
        }
        WeekUiState(
            weekStart = start,
            days = days,
            hasVisibleCalendars = ids.isNotEmpty(),
            today = LocalDate.now(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WeekUiState(weekStart = startOfWeek(LocalDate.now())),
    )

    fun nextWeek() {
        _weekStart.value = _weekStart.value.plusWeeks(1)
    }

    fun previousWeek() {
        _weekStart.value = _weekStart.value.minusWeeks(1)
    }

    fun goToThisWeek() {
        _weekStart.value = startOfWeek(LocalDate.now())
    }

    companion object {
        fun startOfWeek(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
            val diff = (date.dayOfWeek.value - firstDay.value + 7) % 7
            return date.minusDays(diff.toLong())
        }
    }
}
