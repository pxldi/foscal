package app.foscal.ui.agenda

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
    /**
     * The edges of the loaded window, which are *not* [firstDate] and [lastDate].
     *
     * Paging has to be gated on how far has been asked for, not on how far the events happen to
     * reach: an agenda skips empty days, so once you scroll past the last event those two stop
     * moving even as the window keeps growing. Gating on them made the list refuse to load any
     * further the moment it ran out of events — which looked like a hard limit but was really the
     * paging guard latching shut.
     */
    val windowStart: LocalDate = today,
    val windowEnd: LocalDate = today,
    /** False until the first read has come back, so an empty list can be told from a pending one. */
    val loaded: Boolean = false,
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
     * The row for [date], or the first one after it.
     *
     * An agenda skips empty days, so the day asked for often has no row of its own; and the window
     * only spans a couple of months either side of today, so a distant date has nothing near it at
     * all. Both land on the last row rather than nowhere. -1 when there is nothing to show.
     */
    fun indexOnOrAfter(date: LocalDate): Int = items
        .indexOfFirst { it is AgendaItem.Day && !it.day.date.isBefore(date) }
        .takeIf { it >= 0 }
        ?: items.lastIndex

    /** Where to park the list on first load. */
    val todayIndex: Int = indexOnOrAfter(today)

    val firstDate: LocalDate? = days.firstOrNull()?.date
    val lastDate: LocalDate? = days.lastOrNull()?.date
}

/**
 * How far the agenda may page in each direction.
 *
 * Ten years is past the point of usefulness for scrolling — you would jump with the month picker
 * long before — but the window is re-queried whole each time it grows, so it does need an end.
 */
private const val AGENDA_MAX_DAYS = 3650L

private data class AgendaWindow(val pastDays: Long = 60L, val futureDays: Long = 60L)

private data class AgendaBounds(
    val today: LocalDate,
    val range: AgendaWindow,
    val from: Instant,
    val to: Instant,
)

/**
 * How much further each page reaches.
 *
 * Generous because a page is not guaranteed to contain anything: across a quiet stretch of calendar
 * the list keeps asking until it finds days with events, and a small page turns that into a long
 * run of provider queries.
 */
private const val AGENDA_PAGE_DAYS = 120L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
    private val pendingDeletes: PendingDeletes,
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
        .withoutPendingDeletes(pendingDeletes)

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
            windowStart = firstVisible,
            windowEnd = lastVisible,
            loaded = true,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgendaUiState(today = LocalDate.now(zone)),
    )

    fun loadOlder() {
        val next = (window.value.pastDays + AGENDA_PAGE_DAYS).coerceAtMost(AGENDA_MAX_DAYS)
        window.value = window.value.copy(pastDays = next)
    }

    fun loadNewer() {
        val next = (window.value.futureDays + AGENDA_PAGE_DAYS).coerceAtMost(AGENDA_MAX_DAYS)
        window.value = window.value.copy(futureDays = next)
    }
}
