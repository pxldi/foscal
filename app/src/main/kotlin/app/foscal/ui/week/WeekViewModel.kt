package app.foscal.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.resolveEventTimezone
import app.foscal.ui.common.TimelineDay
import app.foscal.ui.util.Dates
import app.foscal.ui.util.visibleCalendarIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class WeekUiState(
    /** The first day on screen. Snapped to the week's first day only when the span is a whole week. */
    val anchor: LocalDate,
    val spanDays: Int = 7,
    val days: List<TimelineDay> = emptyList(),
    /**
     * Every loaded day, not just the visible ones.
     *
     * The screen slides one page over another, and during that slide the outgoing page has to keep
     * drawing the events it had. Handing it a prebuilt list for the *current* anchor would repaint
     * the page on its way out with the incoming page's events, so it looks up its own days here
     * instead — the same trick the month grid uses.
     */
    val eventsByDay: Map<LocalDate, List<Event>> = emptyMap(),
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
    private val _anchor = MutableStateFlow(startOfWeek(LocalDate.now(zone)))
    private val _spanDays = MutableStateFlow(7)

    /**
     * Mirrored out of the flow so the navigation methods can snap synchronously.
     *
     * They are plain functions called from a click, not suspending ones, and re-snapping a whole
     * week is not something to do a frame late.
     */
    private var weekStart: DayOfWeek = Preferences.DEFAULT_FIRST_DAY

    init {
        viewModelScope.launch {
            prefs.firstDayOfWeek.collect { day ->
                weekStart = day
                // A week already on screen has to re-snap, or it keeps starting on the old day
                // until the user pages away from it.
                if (_spanDays.value == 7) _anchor.value = startOfWeek(_anchor.value, day)
            }
        }
    }

    private val today = Dates.todayFlow(zone)

    private val calendarIds = visibleCalendarIds(repository, prefs)

    private val bounds = combine(_anchor, _spanDays) { start, span ->
        // Look back far enough that multi-day events which started before this window but are
        // still running get returned by the provider; pad the end for UTC all-day edge cases.
        // A page either side of the visible one as well, so the page sliding out during a swipe
        // still has its events and the one sliding in already has its own.
        val from = start.minusDays(31L + span).atStartOfDay(zone).toInstant()
        val to = start.plusDays(2L * span + 1L).atStartOfDay(zone).toInstant()
        from to to
    }

    private val events = combine(calendarIds, bounds) { ids, range -> ids to range }
        .flatMapLatest { (ids, range) ->
            repository.observeEvents(ids, range.first, range.second)
        }

    val state: StateFlow<WeekUiState> = combine(
        combine(_anchor, _spanDays) { anchor, span -> anchor to span },
        events,
        calendarIds,
        today,
    ) { (start, span), evts, ids, currentDate ->
        val byDate = evts
            .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.start } }
        val days = (0 until span).map { offset ->
            val date = start.plusDays(offset.toLong())
            TimelineDay(date, byDate[date].orEmpty())
        }
        WeekUiState(
            anchor = start,
            spanDays = span,
            days = days,
            eventsByDay = byDate,
            hasVisibleCalendars = ids.isNotEmpty(),
            today = currentDate,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        WeekUiState(anchor = startOfWeek(LocalDate.now(zone)), today = LocalDate.now(zone)),
    )

    /**
     * Switches how many days are on screen, keeping the date you were looking at on screen.
     *
     * Widening to a whole week snaps back to that day's Monday, because a week that starts on a
     * Thursday is not a week. Narrowing keeps the anchor as-is: the first column stays put and the
     * later ones fall away, which is far less disorienting than jumping to today.
     */
    fun setSpan(days: Int) {
        if (days == _spanDays.value) return
        _spanDays.value = days
        if (days == 7) _anchor.value = startOfWeek(_anchor.value, weekStart)
    }

    /**
     * Opens [spanDays] days beginning at [date], so arriving from another view lands on the day the
     * user was already looking at rather than on today.
     *
     * A week still snaps to that day's Monday — a week that begins on a Thursday is not a week —
     * but Day and 3 Days start exactly where they were told to.
     */
    fun showFrom(date: LocalDate, spanDays: Int) {
        _spanDays.value = spanDays
        _anchor.value = if (spanDays == 7) startOfWeek(date, weekStart) else date
    }

    fun next() {
        _anchor.value = _anchor.value.plusDays(_spanDays.value.toLong())
    }

    fun previous() {
        _anchor.value = _anchor.value.minusDays(_spanDays.value.toLong())
    }

    fun goToToday() {
        val now = LocalDate.now(zone)
        _anchor.value = if (_spanDays.value == 7) startOfWeek(now, weekStart) else now
    }

    fun moveEvent(event: Event, newStartMillis: Long, newEndMillis: Long) {
        if (event.allDay) return
        viewModelScope.launch {
            // Carry every reminder across the move; updateEvent rewrites the whole set, so
            // dropping to just the earliest one here would delete the rest.
            val reminders = repository.getReminderMinutes(event.id).distinct().sorted()
            val input = EventInput(
                calendarId = event.calendarId,
                title = event.title,
                location = event.location,
                description = event.description,
                start = Instant.ofEpochMilli(newStartMillis),
                end = Instant.ofEpochMilli(newEndMillis),
                allDay = false,
                timezone = resolveEventTimezone(event.timezone, zone),
                frequency = Frequency.NONE,
                rrule = null,
                reminderMinutes = reminders,
            )
            if (event.isRecurring) {
                repository.updateEventInstance(event.id, event.start.toEpochMilli(), input)
            } else {
                repository.updateEvent(event.id, input)
            }
        }
    }

    companion object {
        fun startOfWeek(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
            val diff = (date.dayOfWeek.value - firstDay.value + 7) % 7
            return date.minusDays(diff.toLong())
        }
    }
}
