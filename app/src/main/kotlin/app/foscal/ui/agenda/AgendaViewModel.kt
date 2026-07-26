package app.foscal.ui.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Event
import app.foscal.ui.util.Dates
import app.foscal.ui.util.visibleCalendarIds
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
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class AgendaDay(
    val date: LocalDate,
    val events: List<Event>,
)

/**
 * One row of the agenda list.
 *
 * The list is flat rather than nested months so a `LazyColumn` index *is* an index into this list:
 * the paging triggers and the initial scroll-to-today both work in list indices, and nesting would
 * put them permanently out of step with the headers interleaved between days.
 */
sealed interface AgendaItem {
    /** Pinned while its month is on screen — an agenda skips empty days, so without it there is
     *  nothing on screen naming the month or year being looked at. */
    data class MonthHeader(val yearMonth: YearMonth) : AgendaItem

    data class Day(val day: AgendaDay) : AgendaItem
}

data class AgendaUiState(
    val days: List<AgendaDay> = emptyList(),
    val hasVisibleCalendars: Boolean = true,
    val today: LocalDate = LocalDate.now(),
) {
    /** [days] with a [AgendaItem.MonthHeader] inserted wherever the month changes. */
    val items: List<AgendaItem> = buildList {
        var month: YearMonth? = null
        for (day in days) {
            val dayMonth = YearMonth.from(day.date)
            if (dayMonth != month) {
                month = dayMonth
                add(AgendaItem.MonthHeader(dayMonth))
            }
            add(AgendaItem.Day(day))
        }
    }

    /**
     * Where to park the list on first load: the first day that is not in the past, or the last row
     * when the whole agenda is behind us. -1 when there is nothing to show.
     */
    val todayIndex: Int = items
        .indexOfFirst { it is AgendaItem.Day && !it.day.date.isBefore(today) }
        .takeIf { it >= 0 }
        ?: items.lastIndex

    val firstDate: LocalDate? = days.firstOrNull()?.date
    val lastDate: LocalDate? = days.lastOrNull()?.date
}

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

    private val calendarIds = visibleCalendarIds(repository, prefs)

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
