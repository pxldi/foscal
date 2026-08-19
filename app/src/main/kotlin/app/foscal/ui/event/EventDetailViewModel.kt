package app.foscal.ui.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
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
) {
    /**
     * The user's own row, when they were invited to this rather than having made it.
     *
     * Matched on the calendar's owner address, which is what the provider stores on the attendee
     * row. An event with no guest list, or one whose list does not name the user, has nothing to
     * answer and shows no reply buttons.
     */
    val selfAttendee: Attendee? = calendar?.ownerName
        ?.takeIf { it.isNotBlank() }
        ?.let { owner -> attendees.firstOrNull { it.email.equals(owner, ignoreCase = true) } }

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
    fun reply(status: AttendeeStatus) {
        val eventId = _state.value.event?.id ?: return
        viewModelScope.launch {
            if (repository.setSelfAttendeeStatus(eventId, status)) {
                val attendees = repository.getAttendees(eventId)
                _state.update { it.copy(attendees = attendees) }
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
