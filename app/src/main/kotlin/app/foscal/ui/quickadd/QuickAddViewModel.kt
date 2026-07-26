package app.foscal.ui.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Calendar
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.QuickAddParser
import app.foscal.core.model.QuickAddResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

data class QuickAddUiState(
    val loading: Boolean = true,
    val query: String = "",
    val calendars: List<Calendar> = emptyList(),
    val selectedCalendarId: Long? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    val preview: QuickAddResult get() = QuickAddParser.parse(query)
    val canSave: Boolean get() = !loading && !saving && selectedCalendarId != null
}

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: Preferences,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(QuickAddUiState())
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val hidden = prefs.hiddenCalendarIds.first()
            val visible = repository.getCalendars()
                .filter { it.visible && it.id.toString() !in hidden }
            _state.value = QuickAddUiState(
                loading = false,
                calendars = visible,
                selectedCalendarId = visible.firstOrNull()?.id,
            )
        }
    }

    fun updateQuery(value: String) = mutate { it.copy(query = value) }

    fun selectCalendar(id: Long) = mutate { it.copy(selectedCalendarId = id) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        mutate { it.copy(saving = true) }
        viewModelScope.launch {
            val parsed = QuickAddParser.parse(current.query)
            val start = resolveStart(parsed)
            val end = if (parsed.allDay) start.plusMillis(86_400_000L) else start.plusSeconds(3_600L)
            repository.createEvent(
                EventInput(
                    calendarId = current.selectedCalendarId!!,
                    title = parsed.title,
                    location = null,
                    description = null,
                    start = start,
                    end = end,
                    allDay = parsed.allDay,
                    timezone = if (parsed.allDay) ZoneOffset.UTC.id else zone.id,
                    frequency = Frequency.NONE,
                    rrule = null,
                    reminderMinutes = listOf(prefs.defaultReminderMinutes.first() ?: 15),
                ),
            )
            mutate { it.copy(saving = false, finished = true) }
        }
    }

    private fun resolveStart(parsed: QuickAddResult): Instant {
        val date = parsed.date ?: LocalDate.now()
        return if (parsed.allDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant()
        } else {
            val time = parsed.time ?: nextHour()
            date.atTime(time).atZone(zone).toInstant()
        }
    }

    private fun nextHour(): LocalTime =
        java.time.ZonedDateTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0).toLocalTime()

    private fun mutate(transform: (QuickAddUiState) -> QuickAddUiState) {
        _state.value = transform(_state.value)
    }
}
