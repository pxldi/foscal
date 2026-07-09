package app.foscal.ui.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: Event? = null,
    val calendar: Calendar? = null,
    /** Whether the opt-in map is on, deciding in-app OSM viewer vs external geo: intent. */
    val mapsEnabled: Boolean = false,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: CalendarRepository,
    prefs: Preferences,
) : ViewModel() {

    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.osmMapsEnabled.collect { enabled -> _state.update { it.copy(mapsEnabled = enabled) } }
        }
    }

    fun load(eventId: Long, instanceStartMillis: Long = 0L, showLoading: Boolean = true) {
        if (showLoading) _state.update { it.copy(loading = true, event = null, calendar = null) }
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val from = LocalDate.now().minusYears(2).atStartOfDay(zone).toInstant()
            val to = LocalDate.now().plusYears(2).atStartOfDay(zone).toInstant()
            val calendars = repository.getCalendars()
            val allIds = calendars.map { it.id }.toSet()
            val matches = repository.getEvents(allIds, from, to).filter { it.id == eventId }
            // Prefer the exact occurrence the user tapped; fall back to the first (e.g. when
            // opened from a notification, which only carries the event id).
            val event = matches.firstOrNull { it.start.toEpochMilli() == instanceStartMillis }
                ?: matches.firstOrNull()
            val cal = calendars.firstOrNull { it.id == event?.calendarId }
            _state.update { it.copy(loading = false, event = event, calendar = cal) }
        }
    }
}
