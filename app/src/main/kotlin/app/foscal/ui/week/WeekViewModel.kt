package app.foscal.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.RecurrenceRules
import app.foscal.core.model.resolveEventTimezone
import app.foscal.ui.common.TimelineDay
import app.foscal.ui.editor.RecurrenceScope
import app.foscal.ui.feedback.PendingDeletes
import app.foscal.ui.feedback.UserMessages
import app.foscal.ui.feedback.withoutPendingDeletes
import app.foscal.ui.util.DayWindow
import app.foscal.ui.util.Dates
import app.foscal.ui.util.visibleCalendarIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    private val pendingDeletes: PendingDeletes,
    private val messages: UserMessages,
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

    /**
     * The loaded range, which deliberately does not follow the anchor swipe for swipe. Paging
     * inside it costs nothing at all; only running out of it goes back to the provider.
     */
    private val window = combine(_anchor, _spanDays) { start, span ->
        start to start.plusDays(span - 1L)
    }.scan(DayWindow.around(_anchor.value, _anchor.value.plusDays(6))) { current, (first, last) ->
        DayWindow.keepOrMove(current, first, last)
    }.distinctUntilChanged()

    private val events = combine(calendarIds, window) { ids, w -> ids to w }
        .flatMapLatest { (ids, w) ->
            repository.observeEvents(ids, w.startInstant(zone), w.endInstant(zone))
        }
        .withoutPendingDeletes(pendingDeletes)

    /**
     * Grouped once per load rather than once per swipe. The map is handed to the screen as-is, so
     * keeping the same instance across a page turn is what stops both the outgoing and incoming
     * pages rebuilding their day lists in the middle of the slide.
     */
    private val eventsByDay = events.map { evts ->
        evts
            .flatMap { e -> e.spannedDays(zone).map { d -> d to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    val state: StateFlow<WeekUiState> = combine(
        combine(_anchor, _spanDays) { anchor, span -> anchor to span },
        eventsByDay,
        calendarIds,
        today,
    ) { (start, span), byDate, ids, currentDate ->
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

    private val _moveRefusals = MutableStateFlow(0)

    /**
     * Counts the moves the provider refused. The grid holds a dropped block where it landed until
     * new data arrives, and a refused write sends none, so this is its cue to put the block back.
     */
    val moveRefusals: StateFlow<Int> = _moveRefusals.asStateFlow()

    /**
     * Puts [event] down at a new time, at the breadth [scope] asks for.
     *
     * [scope] is only consulted for a series; a one-off has a single meaning and is written
     * straight through. Dragging one occurrence of a series used to *always* mean "just this one",
     * silently — the moved occurrence became an exception with no rule on it, so the editor then
     * showed it as repeating "Once" and it read as though the drag had deleted the repeat.
     */
    fun moveEvent(
        event: Event,
        newStartMillis: Long,
        newEndMillis: Long,
        scope: RecurrenceScope = RecurrenceScope.SINGLE,
    ) {
        if (event.allDay) return
        viewModelScope.launch {
            // Carry every reminder across the move; updateEvent rewrites the whole set, so
            // dropping to just the earliest one here would delete the rest.
            val reminders = repository.getReminderMinutes(event.id).distinct().sorted()
            // The event's *own* colour, read rather than taken from Event.color: that field is the
            // resolved DISPLAY_COLOR and falls back to the calendar's, so writing it back would
            // pin the calendar's colour onto the event as if the user had chosen it. Omitting it
            // was worse — EventInput.color defaults to null and null means "clear the column", so
            // every drag quietly stripped the colour off whatever it moved.
            val color = repository.getEventColor(event.id)
            val start = Instant.ofEpochMilli(newStartMillis)
            val end = Instant.ofEpochMilli(newEndMillis)
            // A one-off has nothing to decide.
            val effective = if (event.isRecurring) scope else RecurrenceScope.SINGLE

            fun input(
                at: Instant,
                until: Instant,
                keepRule: Boolean,
            ) = EventInput(
                calendarId = event.calendarId,
                title = event.title,
                location = event.location,
                description = event.description,
                start = at,
                end = until,
                allDay = false,
                timezone = resolveEventTimezone(event.timezone, zone),
                // Frequency is only a NONE/not-NONE switch once an explicit rule is supplied, but
                // it has to agree with the rule or the provider is handed DTEND and an RRULE at
                // once, which it rejects outright.
                frequency = if (keepRule) {
                    RecurrenceRules.parse(event.rrule).frequency
                } else {
                    Frequency.NONE
                },
                rrule = if (keepRule) event.rrule else null,
                reminderMinutes = reminders,
                color = color,
            )

            val moved = when {
                !event.isRecurring ->
                    repository.updateEvent(event.id, input(start, end, keepRule = false))

                effective == RecurrenceScope.SINGLE ->
                    repository.updateEventInstance(
                        event.id,
                        event.start.toEpochMilli(),
                        input(start, end, keepRule = false),
                    )

                effective == RecurrenceScope.THIS_AND_FOLLOWING ->
                    repository.updateEventFollowing(
                        event.id,
                        event.start.toEpochMilli(),
                        input(start, end, keepRule = true),
                        // The rule itself was not edited, so the split series keeps the master's
                        // pattern with its COUNT rebased to what is left of it.
                        rebaseCount = true,
                    )

                else -> {
                    // The whole series shifts by however far this occurrence moved. Setting the
                    // master's DTSTART to the dropped time instead would move the series to
                    // *this* occurrence's date, which for anything past the first is a jump of
                    // however many repeats have already happened.
                    val master = repository.getEventOccurrence(event.id, 0L)
                    val delta = newStartMillis - event.start.toEpochMilli()
                    master != null && repository.updateEvent(
                        event.id,
                        input(
                            master.start.plusMillis(delta),
                            master.end.plusMillis(delta),
                            keepRule = true,
                        ),
                    )
                }
            }
            if (!moved) {
                _moveRefusals.update { it + 1 }
                messages.post("Couldn't move the event")
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
