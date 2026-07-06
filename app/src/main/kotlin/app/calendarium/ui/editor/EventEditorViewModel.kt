package app.calendarium.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.EventInput
import app.calendarium.core.model.Frequency
import app.calendarium.core.model.RecurrenceRules
import app.calendarium.core.model.RecurrenceSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/** Which action is awaiting a "this event vs. all events" choice for a recurring series. */
enum class RecurrenceScopePrompt { SAVE, DELETE }

data class EditorUiState(
    val loading: Boolean = true,
    val eventId: Long = 0L,
    val isEditing: Boolean = false,
    val isRecurring: Boolean = false,
    val originalInstanceTime: Long = 0L,
    val originalRrule: String? = null,
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
    val interval: Int = 1,
    val recurrenceEndDate: LocalDate? = null,
    val recurrenceCount: Int? = null,
    val byWeekday: Set<DayOfWeek> = emptySet(),
    // False until the user touches a recurrence control (frequency or a custom field). While false,
    // the original CalDAV RRULE is preserved verbatim on save; once true we rebuild from the controls.
    val recurrenceDirty: Boolean = false,
    val showCustomRecurrence: Boolean = false,
    val reminderMinutesBefore: Int? = 15,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val scopePrompt: RecurrenceScopePrompt? = null,
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
                val matches = repository.getEvents(allIds, from, to).filter { it.id == eventId }
                // For a recurring event, many instances share the same id; startArg carries the
                // begin time of the specific occurrence the user tapped so we edit the right one.
                val event = startArg?.let { s -> matches.firstOrNull { it.start.toEpochMilli() == s } }
                    ?: matches.firstOrNull()
                val reminders = repository.getReminderMinutes(eventId)
                if (event != null) {
                    val cal = event.calendarId
                    val startZ = event.start.atZone(zone)
                    val endZ = event.end.atZone(zone)
                    val spec = RecurrenceRules.parse(event.rrule)
                    _state.value = EditorUiState(
                        loading = false,
                        eventId = eventId,
                        isEditing = true,
                        isRecurring = event.isRecurring,
                        originalInstanceTime = event.start.toEpochMilli(),
                        originalRrule = event.rrule,
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
                        frequency = spec.frequency,
                        interval = spec.interval,
                        recurrenceEndDate = spec.until,
                        recurrenceCount = spec.count,
                        byWeekday = spec.byWeekday,
                        // Expand the custom panel when the loaded rule actually uses the extra knobs
                        // so the user sees the real interval/end/by-weekday rather than a bare chip.
                        showCustomRecurrence = spec.isCustom,
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
    fun updateFrequency(freq: Frequency) = mutate {
        it.copy(frequency = freq, recurrenceDirty = true)
    }
    fun updateInterval(value: Int) = mutate {
        it.copy(interval = value.coerceAtLeast(1), recurrenceDirty = true)
    }
    fun updateRecurrenceCount(value: Int?) = mutate {
        // COUNT and UNTIL are mutually exclusive.
        it.copy(
            recurrenceCount = value,
            recurrenceEndDate = if (value != null) null else it.recurrenceEndDate,
            recurrenceDirty = true,
        )
    }
    fun updateRecurrenceEndDate(date: LocalDate?) = mutate {
        it.copy(
            recurrenceEndDate = date,
            recurrenceCount = if (date != null) null else it.recurrenceCount,
            recurrenceDirty = true,
        )
    }
    fun toggleByWeekday(day: DayOfWeek) = mutate {
        val next = if (day in it.byWeekday) it.byWeekday - day else it.byWeekday + day
        it.copy(byWeekday = next, recurrenceDirty = true)
    }
    fun toggleCustomRecurrence() = mutate { it.copy(showCustomRecurrence = !it.showCustomRecurrence) }
    fun updateReminder(minutes: Int?) = mutate { it.copy(reminderMinutesBefore = minutes) }
    fun selectCalendar(id: Long) = mutate { it.copy(selectedCalendarId = id) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        // Editing one occurrence of a series: ask whether to change just it or the whole series.
        if (current.isEditing && current.isRecurring) {
            mutate { it.copy(scopePrompt = RecurrenceScopePrompt.SAVE) }
            return
        }
        performSave(applyToWholeSeries = true)
    }

    fun delete() {
        val current = _state.value
        if (!current.isEditing || current.eventId == 0L) return
        if (current.isRecurring) {
            mutate { it.copy(scopePrompt = RecurrenceScopePrompt.DELETE) }
            return
        }
        performDelete(applyToWholeSeries = true)
    }

    fun dismissScopePrompt() = mutate { it.copy(scopePrompt = null) }

    /** Resolves a recurrence scope prompt. [wholeSeries] false edits/deletes only this occurrence. */
    fun resolveScope(wholeSeries: Boolean) {
        val prompt = _state.value.scopePrompt ?: return
        mutate { it.copy(scopePrompt = null) }
        when (prompt) {
            RecurrenceScopePrompt.SAVE -> performSave(applyToWholeSeries = wholeSeries)
            RecurrenceScopePrompt.DELETE -> performDelete(applyToWholeSeries = wholeSeries)
        }
    }

    private fun performSave(applyToWholeSeries: Boolean) {
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
            // Preserve the original rule verbatim unless the user actually edited recurrence,
            // so externally-synced CalDAV rules (BYMONTHDAY, BYSETPOS, …) survive unrelated edits.
            // Once they touch a recurrence control, rebuild from the current custom controls.
            val rrule = when {
                current.frequency == Frequency.NONE -> null
                current.recurrenceDirty -> RecurrenceRules.build(
                    RecurrenceSpec(
                        frequency = current.frequency,
                        interval = current.interval,
                        count = current.recurrenceCount,
                        until = current.recurrenceEndDate,
                        byWeekday = current.byWeekday,
                    ),
                    current.allDay,
                    zone,
                )
                else -> current.originalRrule
            }
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
                rrule = rrule,
                reminderMinutesBefore = current.reminderMinutesBefore,
            )
            when {
                !current.isEditing -> repository.createEvent(input)
                current.isRecurring && !applyToWholeSeries ->
                    repository.updateEventInstance(current.eventId, current.originalInstanceTime, input)
                else -> repository.updateEvent(current.eventId, input)
            }
            mutate { it.copy(saving = false, finished = true) }
        }
    }

    private fun performDelete(applyToWholeSeries: Boolean) {
        val current = _state.value
        mutate { it.copy(saving = true) }
        viewModelScope.launch {
            if (current.isRecurring && !applyToWholeSeries) {
                repository.deleteEventInstance(current.eventId, current.originalInstanceTime)
            } else {
                repository.deleteEvent(current.eventId)
            }
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
