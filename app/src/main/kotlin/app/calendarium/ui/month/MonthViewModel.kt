package app.calendarium.ui.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.Preferences
import app.calendarium.core.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class MonthUiState(
    val visibleMonth: YearMonth,
    val selectedDate: LocalDate?,
    val eventsByDay: Map<LocalDate, List<Event>> = emptyMap(),
    val hasVisibleCalendars: Boolean = true,
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val _visibleMonth = MutableStateFlow(YearMonth.now())
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val calendarIds = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
    ) { all, hidden ->
        all.asSequence()
            .filter { it.visible }
            .filter { it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
    }

    private val monthBounds = _visibleMonth.mapMonthToRange(zone)

    private val events = combine(calendarIds, monthBounds) { ids, range ->
        Triple(ids, range.first, range.second)
    }.flatMapLatest { (ids, from, to) ->
        repository.observeEvents(ids, from, to)
    }

    val state: StateFlow<MonthUiState> = combine(
        _visibleMonth,
        _selectedDate,
        events,
        calendarIds,
    ) { month, selected, evts, ids ->
        MonthUiState(
            visibleMonth = month,
            selectedDate = selected,
            eventsByDay = evts
                .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
                .groupBy({ it.first }, { it.second }),
            hasVisibleCalendars = ids.isNotEmpty(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MonthUiState(YearMonth.now(), null),
    )

    fun selectDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    fun nextMonth() = showMonth(_visibleMonth.value.plusMonths(1))

    fun previousMonth() = showMonth(_visibleMonth.value.minusMonths(1))

    fun goToMonth(month: YearMonth) = showMonth(month)

    private fun showMonth(month: YearMonth) {
        _visibleMonth.value = month
        // Move the selection into the month now on screen so the day preview never lags behind the
        // grid: today when landing on the current month, otherwise its first day.
        val today = LocalDate.now()
        _selectedDate.value = if (month == YearMonth.from(today)) today else month.atDay(1)
    }
}

private fun kotlinx.coroutines.flow.Flow<YearMonth>.mapMonthToRange(
    zone: ZoneId,
): kotlinx.coroutines.flow.Flow<Pair<Instant, Instant>> =
    map { month ->
        // Cover a wider window than the visible month so that adjacent pages in the
        // HorizontalPager are populated as the user swipes (and to absorb grid spillover).
        val start = month.minusMonths(2).atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(2).atEndOfMonth()
            .atTime(23, 59, 59).atZone(zone).toInstant()
        start to end
    }
