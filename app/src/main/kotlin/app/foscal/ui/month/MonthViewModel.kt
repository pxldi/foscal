package app.foscal.ui.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Event
import app.foscal.ui.feedback.PendingDeletes
import app.foscal.ui.feedback.withoutPendingDeletes
import app.foscal.ui.util.DayWindow
import app.foscal.ui.util.Dates
import app.foscal.ui.util.monthCalendarIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
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
    val firstDayOfWeek: DayOfWeek = Preferences.DEFAULT_FIRST_DAY,
    val showWeekNumbers: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
    private val pendingDeletes: PendingDeletes,
) : ViewModel() {

    private val _visibleMonth = MutableStateFlow(YearMonth.now())
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = Dates.todayFlow(zone)

    private val calendarIds = monthCalendarIds(repository, prefs)

    /**
     * The loaded range, which deliberately does not follow the visible month. Paging inside it
     * costs nothing at all; only running out of it goes back to the provider.
     */
    private val window = _visibleMonth
        .map { month -> month.atDay(1) to month.atEndOfMonth() }
        .scan(
            DayWindow.around(
                _visibleMonth.value.atDay(1),
                _visibleMonth.value.atEndOfMonth(),
            ),
        ) { current, (first, last) -> DayWindow.keepOrMove(current, first, last) }
        .distinctUntilChanged()

    private val events = combine(calendarIds, window) { ids, w -> ids to w }
        .flatMapLatest { (ids, w) ->
            repository.observeEvents(ids, w.startInstant(zone), w.endInstant(zone))
        }
        .withoutPendingDeletes(pendingDeletes)

    /**
     * Grouped once per load rather than once per month change. The map is handed to the screen
     * as-is, so keeping the same instance across a page turn is what stops both the outgoing and
     * incoming grids rebuilding in the middle of the slide.
     */
    private val eventsByDay = combine(events, prefs.monthMinimumMinutes) { evts, minimum ->
        evts
            // All-day events are never dropped: being all day is exactly what makes them worth a
            // month cell, and they have no length to compare in the first place.
            .filter { it.allDay || minimum <= 0 || it.durationMillis >= minimum * 60_000L }
            .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
            .groupBy({ it.first }, { it.second })
    }

    private val gridPrefs = combine(
        prefs.firstDayOfWeek,
        prefs.showWeekNumbers,
    ) { firstDay, weekNumbers -> firstDay to weekNumbers }

    val state: StateFlow<MonthUiState> = combine(
        combine(_visibleMonth, _selectedDate) { month, selected -> month to selected },
        eventsByDay,
        calendarIds,
        today,
        gridPrefs,
    ) { (month, selected), byDate, ids, currentDate, grid ->
        MonthUiState(
            visibleMonth = month,
            selectedDate = selected,
            eventsByDay = byDate,
            hasVisibleCalendars = ids.isNotEmpty(),
            today = currentDate,
            firstDayOfWeek = grid.first,
            showWeekNumbers = grid.second,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MonthUiState(YearMonth.now(), null, today = LocalDate.now(zone)),
    )

    fun selectDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    /**
     * Shows [date]'s month with [date] selected — how another view hands its focus over, so
     * switching to Month lands on the day being looked at rather than on today.
     */
    fun goToDate(date: LocalDate) {
        _visibleMonth.value = YearMonth.from(date)
        _selectedDate.value = date
    }

    fun nextMonth() = showMonth(_visibleMonth.value.plusMonths(1))

    fun previousMonth() = showMonth(_visibleMonth.value.minusMonths(1))

    fun goToMonth(month: YearMonth) = showMonth(month)

    private fun showMonth(month: YearMonth) {
        _visibleMonth.value = month
        // Move the selection into the month now on screen so the day preview never lags behind the
        // grid: today when landing on the current month, otherwise its first day.
        val today = LocalDate.now(zone)
        _selectedDate.value = if (month == YearMonth.from(today)) today else month.atDay(1)
    }
}

