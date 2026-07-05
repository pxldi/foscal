package app.calendarium.ui.event

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.Event
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class EventDetailUiState(
    val loading: Boolean = true,
    val event: Event? = null,
    val calendar: Calendar? = null,
)

@HiltViewModel
class EventDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CalendarRepository,
) : ViewModel() {

    private val eventId: Long = savedStateHandle.get<String>("eventId")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(EventDetailUiState())
    val state: StateFlow<EventDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val from = LocalDate.now().minusYears(1).atStartOfDay(zone).toInstant()
            val to = LocalDate.now().plusYears(1).atStartOfDay(zone).toInstant()
            val calendars = repository.getCalendars()
            val allIds = calendars.map { it.id }.toSet()
            val event = repository.getEvents(allIds, from, to).firstOrNull { it.id == eventId }
            val cal = calendars.firstOrNull { it.id == event?.calendarId }
            _state.value = EventDetailUiState(loading = false, event = event, calendar = cal)
        }
    }
}
