package app.calendarium.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.EventInput
import app.calendarium.core.model.Frequency
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

data class EditorUiState(
    val loading: Boolean = true,
    val eventId: Long = 0L,
    val isEditing: Boolean = false,
    val title: String = "",
    val availableCalendars: List<Calendar> = emptyList(),
    val selectedCalendarId: Long? = null,
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val location: String = "",
    val description: String = "",
    val frequency: Frequency = Frequency.NONE,
    val reminderMinutesBefore: Int? = 15,
    val saving: Boolean = false,
    val finished: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank() && selectedCalendarId != null && !saving
}

@HiltViewModel
class EventEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        val eventId = savedStateHandle.get<String>("eventId")?.toLongOrNull() ?: 0L
        val startArg = savedStateHandle.get<String>("start")?.toLongOrNull()
        val endArg = savedStateHandle.get<String>("end")?.toLongOrNull()
        val calArg = savedStateHandle.get<String>("calendarId")?.toLongOrNull()
        load(eventId, startArg, endArg, calArg)
    }

    private fun load(eventId: Long, startArg: Long?, endArg: Long?, calArg: Long?) {
        viewModelScope.launch {
            val hidden = prefs.hiddenCalendarIds.first()
            val defaultReminder = prefs.defaultReminderMinutes.first() ?: 15
            val visible = repository.getCalendars()
                .filter { it.visible && it.id.toString() !in hidden }
            if (eventId > 0L) {
                val allIds = repository.getCalendars().map { it.id }.toSet()
                val from = LocalDate.now().minusYears(2).atStartOfDay(zone).toInstant()
                val to = LocalDate.now().plusYears(2).atStartOfDay(zone).toInstant()
                val event = repository.getEvents(allIds, from, to).firstOrNull { it.id == eventId }
                val reminders = repository.getReminderMinutes(eventId)
                if (event != null) {
                    val cal = event.calendarId
                    val startZ = event.start.atZone(zone)
                    val endZ = event.end.atZone(zone)
                    _state.value = EditorUiState(
                        loading = false,
                        eventId = eventId,
                        isEditing = true,
                        title = event.title,
                        availableCalendars = visible,
                        selectedCalendarId = cal,
                        allDay = event.allDay,
                        startDate = startZ.toLocalDate(),
                        startTime = if (event.allDay) LocalTime.MIDNIGHT else startZ.toLocalTime(),
                        endDate = endZ.toLocalDate(),
                        endTime = if (event.allDay) LocalTime.MIDNIGHT else endZ.toLocalTime(),
                        location = event.location.orEmpty(),
                        description = event.description.orEmpty(),
                        frequency = Frequency.NONE, // existing RRULE parsing deferred
                        reminderMinutesBefore = reminders.minOrNull(),
                    )
                    return@launch
                }
            }
            // new event
            val defaultStart = startArg?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?: nextHourFromNow()
            val defaultEnd = endArg?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?: defaultStart.plusHours(1)
            val defaultCalendar = calArg ?: visible.firstOrNull()?.id
            _state.value = EditorUiState(
                loading = false,
                isEditing = false,
                availableCalendars = visible,
                selectedCalendarId = defaultCalendar,
                startDate = defaultStart.toLocalDate(),
                startTime = defaultStart.toLocalTime(),
                endDate = defaultEnd.toLocalDate(),
                endTime = defaultEnd.toLocalTime(),
                reminderMinutesBefore = defaultReminder,
            )
        }
    }

    private fun nextHourFromNow() =
        java.time.ZonedDateTime.now(zone)
            .plusHours(1)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

    fun updateTitle(value: String) = mutate { it.copy(title = value) }
    fun updateLocation(value: String) = mutate { it.copy(location = value) }
    fun updateDescription(value: String) = mutate { it.copy(description = value) }
    fun updateAllDay(value: Boolean) = mutate { it.copy(allDay = value) }
    fun updateStartDate(date: LocalDate) = mutate {
        it.copy(startDate = date, endDate = if (date.isAfter(it.endDate)) date else it.endDate)
    }
    fun updateStartTime(time: LocalTime) = mutate { it.copy(startTime = time) }
    fun updateEndDate(date: LocalDate) = mutate { it.copy(endDate = date) }
    fun updateEndTime(time: LocalTime) = mutate { it.copy(endTime = time) }
    fun updateFrequency(freq: Frequency) = mutate { it.copy(frequency = freq) }
    fun updateReminder(minutes: Int?) = mutate { it.copy(reminderMinutesBefore = minutes) }
    fun selectCalendar(id: Long) = mutate { it.copy(selectedCalendarId = id) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        mutate { it.copy(saving = true) }
        viewModelScope.launch {
            val startInstant = combineInstant(current.startDate, current.startTime, current.allDay)
            val endInstant = combineInstant(
                if (current.allDay) current.endDate.plusDays(1) else current.endDate,
                if (current.allDay) LocalTime.MIDNIGHT else current.endTime,
                current.allDay,
            )
            val input = EventInput(
                calendarId = current.selectedCalendarId!!,
                title = current.title,
                location = current.location.ifBlank { null },
                description = current.description.ifBlank { null },
                start = startInstant,
                end = endInstant,
                allDay = current.allDay,
                timezone = if (current.allDay) ZoneOffset.UTC.id else zone.id,
                frequency = current.frequency,
                reminderMinutesBefore = current.reminderMinutesBefore,
            )
            if (current.isEditing) {
                repository.updateEvent(current.eventId, input)
            } else {
                repository.createEvent(input)
            }
            mutate { it.copy(saving = false, finished = true) }
        }
    }

    fun delete() {
        val current = _state.value
        if (!current.isEditing || current.eventId == 0L) return
        mutate { it.copy(saving = true) }
        viewModelScope.launch {
            repository.deleteEvent(current.eventId)
            mutate { it.copy(saving = false, finished = true) }
        }
    }

    private fun combineInstant(date: LocalDate, time: LocalTime, allDay: Boolean): Instant {
        return if (allDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant()
        } else {
            date.atTime(time).atZone(zone).toInstant()
        }
    }

    private fun mutate(transform: (EditorUiState) -> EditorUiState) {
        _state.value = transform(_state.value)
    }
}
