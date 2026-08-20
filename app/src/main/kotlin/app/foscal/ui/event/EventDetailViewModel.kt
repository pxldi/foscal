package app.foscal.ui.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import app.foscal.ui.editor.RecurrenceScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: Event? = null,
    val calendar: Calendar? = null,
    /** Everyone on the event, organizer first. Empty when it has no guest list. */
    val attendees: List<Attendee> = emptyList(),
    /** Whether the opt-in map is on, deciding in-app OSM viewer vs external geo: intent. */
    val mapsEnabled: Boolean = false,
    val reminderMinutes: List<Int> = emptyList(),
    /**
     * Set when a reply was refused, so the screen can say so.
     *
     * A tap that changes nothing and explains nothing is the worst of the three outcomes: the
     * user cannot tell it from a reply that worked, and will find out from the organiser.
     */
    val replyFailed: Boolean = false,
    /** Whether the user is being asked to confirm a delete. */
    val deletePrompt: Boolean = false,
    /** Set once the event is gone, so the screen showing it can leave. */
    val deleted: Boolean = false,
) {
    /**
     * The user's own row, when they were invited to this rather than having made it.
     *
     * Matched on the calendar's owner address, which is what the provider stores on the attendee
     * row, through [Attendee.normalizeAddress] — the same rule the write uses, so a reply is never
     * offered on a row the write would then fail to find. An event with no attendees, or one whose
     * list does not name the user, has nothing to answer and shows no reply buttons.
     */
    val selfAttendee: Attendee? = calendar?.ownerName
        ?.takeIf { it.isNotBlank() }
        ?.let { Attendee.normalizeAddress(it) }
        ?.let { owner -> attendees.firstOrNull { Attendee.normalizeAddress(it.email) == owner } }

    /** Whether to offer Yes / Maybe / No: someone invited the user and is not the user. */
    val canReply: Boolean = selfAttendee != null && !selfAttendee.isOrganizer
}

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: CalendarRepository,
    prefs: Preferences,
) : ViewModel() {

    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    /**
     * Answers the invitation, then re-reads.
     *
     * Optimistic would be wrong here: the write can be refused — a read-only calendar, a row the
     * provider will not match — and a reply that appears to have been sent and was not is worse
     * than one that visibly did nothing.
     */
    fun askDelete() = _state.update { it.copy(deletePrompt = true) }

    fun dismissDelete() = _state.update { it.copy(deletePrompt = false) }

    /**
     * Removes the event, at the breadth [scope] asks for.
     *
     * The same three repository calls the editor makes, rather than a trip through the editor to
     * reach its delete: this screen already knows which occurrence the user is looking at, and
     * opening a form in order to throw its subject away is a strange way to spend a tap. [scope]
     * is ignored for an event that does not repeat, where there is only one thing it could mean.
     */
    fun delete(scope: RecurrenceScope) {
        val event = _state.value.event ?: return
        _state.update { it.copy(deletePrompt = false) }
        viewModelScope.launch {
            val gone = when {
                event.isRecurring && scope == RecurrenceScope.SINGLE ->
                    repository.deleteEventInstance(event.id, event.start.toEpochMilli())
                event.isRecurring && scope == RecurrenceScope.THIS_AND_FOLLOWING ->
                    repository.deleteEventFollowing(event.id, event.start.toEpochMilli())
                else -> repository.deleteEvent(event.id)
            }
            if (gone) _state.update { it.copy(deleted = true) }
        }
    }

    fun reply(status: AttendeeStatus) {
        val eventId = _state.value.event?.id ?: return
        _state.update { it.copy(replyFailed = false) }
        viewModelScope.launch {
            if (repository.setSelfAttendeeStatus(eventId, status)) {
                val attendees = repository.getAttendees(eventId)
                _state.update { it.copy(attendees = attendees, replyFailed = false) }
            } else {
                _state.update { it.copy(replyFailed = true) }
            }
        }
    }

    init {
        viewModelScope.launch {
            prefs.osmMapsEnabled.collect { enabled -> _state.update { it.copy(mapsEnabled = enabled) } }
        }
    }

    fun load(eventId: Long, instanceStartMillis: Long = 0L, showLoading: Boolean = true) {
        if (showLoading) {
            _state.update {
                it.copy(loading = true, event = null, calendar = null, attendees = emptyList())
            }
        }
        viewModelScope.launch {
            val calendars = repository.getCalendars()
            // Prefer the exact occurrence the user tapped; the repository falls back to the master
            // row when there isn't one (e.g. opened from a notification, which carries only the id).
            val event = repository.getEventOccurrence(eventId, instanceStartMillis)
            val cal = calendars.firstOrNull { it.id == event?.calendarId }
            val reminders = if (event == null) emptyList() else {
                repository.getReminderMinutes(eventId).distinct().sorted()
            }
            // Guests hang off the event row, so a moved occurrence carries its own list while an
            // unmodified one shares the master's — either way the id the lookup returned is right.
            val attendees = event?.let { repository.getAttendees(it.id) }.orEmpty()
            _state.update {
                it.copy(
                    loading = false,
                    event = event,
                    calendar = cal,
                    reminderMinutes = reminders,
                    attendees = attendees,
                )
            }
        }
    }
}
