package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Attendee
import app.foscal.core.model.Calendar
import app.foscal.core.model.CalendarReminderDefaults
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.RecurrenceRules
import app.foscal.core.model.RecurrenceSpec
import app.foscal.core.model.resolveEventTimezone
import app.foscal.ui.feedback.PendingDeletes
import app.foscal.ui.feedback.UserMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

/** How widely a recurring edit or delete applies. */
enum class RecurrenceScope { SINGLE, THIS_AND_FOLLOWING, ALL_EVENTS }

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
    /** Distinct locations from the user's history, for the editor's offline autocomplete. */
    val recentLocations: List<String> = emptyList(),
    /** Whether the opt-in OpenStreetMap "Pick on map" button should be offered. */
    val mapsEnabled: Boolean = false,
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
    /** Every reminder on the event, in minutes before start; sorted, empty when there are none. */
    val reminderMinutes: List<Int> = listOf(15),
    /**
     * Whether the user has chosen the reminders themselves.
     *
     * While false on a new event, switching calendars re-applies that calendar's default — the
     * point of a per-calendar default is that picking the work calendar gives you the work
     * calendar's reminder. Once true it stays true: silently replacing a reminder the user set
     * because they then corrected the calendar would be a bug, not a convenience.
     */
    val remindersTouched: Boolean = false,
    /**
     * Everyone on the event, organizer included. Loaded in full because saving replaces the whole
     * list — the editor has to be able to write back the guests it did not add itself.
     */
    val attendees: List<Attendee> = emptyList(),
    /** What the user has typed into the "Add attendee" field, before it is committed as a chip. */
    val guestDraft: String = "",
    /** A colour for this one event, or null to follow its calendar's. */
    val color: Int? = null,
    /**
     * The `EVENT_TIMEZONE` of the event being edited, or null for a new one. Preserved on save so
     * editing an event authored in another zone (CalDAV, travel) does not re-anchor it to the
     * device zone and shift it for every other client.
     */
    val originalTimezone: String? = null,
    val saving: Boolean = false,
    val finished: Boolean = false,
    /** Set with [finished] when the editor closed on a delete rather than a save. */
    val deleted: Boolean = false,
    val scopePrompt: RecurrenceScopePrompt? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && selectedCalendarId != null && !saving

    /**
     * Whether the guest list on this event is the user's to change.
     *
     * Rewriting the `ATTENDEE` rows of an event someone else organized is not an edit — it is a
     * scheduling message. What a CalDAV server does with one varies (ignore it, reject it, mail
     * every guest a spurious update), so the editor only offers the field for events that are
     * plainly the user's own:
     *
     * - a **new** event — the user is about to organize it;
     * - anything on a **local** calendar — there is no server and no scheduling to get wrong;
     * - an event with **no organizer**, which is what a plain CalDAV event created by a
     *   non-scheduling client looks like; there is nobody whose event it is instead;
     * - an event whose organizer **is** the calendar's owner account.
     */
    val canEditGuests: Boolean
        get() {
            if (!isEditing) return true
            val calendar = availableCalendars.firstOrNull { it.id == selectedCalendarId }
                ?: return true
            if (calendar.isLocal) return true
            val organizer = attendees.firstOrNull { it.isOrganizer } ?: return true
            val owner = calendar.ownerName?.let { Attendee.normalizeAddress(it) }
            return !owner.isNullOrEmpty() &&
                Attendee.normalizeAddress(organizer.email) == owner
        }

    /** Whether the current draft is a new, well-formed address the list does not already have. */
    val canAddGuest: Boolean
        get() = canEditGuests &&
            Attendee.isValidEmail(guestDraft) &&
            attendees.none {
                Attendee.normalizeAddress(it.email) == Attendee.normalizeAddress(guestDraft)
            }
}

@HiltViewModel
class EventEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CalendarRepository,
    private val prefs: Preferences,
    private val messages: UserMessages,
    private val pendingDeletes: PendingDeletes,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * The reminder defaults, snapshotted when the editor opens.
     *
     * Held rather than re-read on every calendar switch: [selectCalendar] is a plain state update
     * and turning it into a suspending read would let a fast double-tap apply the two calendars'
     * defaults out of order.
     */
    private var globalReminderDefault: Int? = null
    private var calendarReminderDefaults: Map<Long, Int?> = emptyMap()

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    init {
        val eventId = savedStateHandle.get<String>("eventId")?.toLongOrNull() ?: 0L
        val copyFrom = savedStateHandle.get<String>("copyFrom")?.toLongOrNull() ?: 0L
        val startArg = savedStateHandle.get<String>("start")?.toLongOrNull()
        val endArg = savedStateHandle.get<String>("end")?.toLongOrNull()
        val calArg = savedStateHandle.get<String>("calendarId")?.toLongOrNull()
        // What another app supplied through an INSERT intent. Empty for every route the app
        // navigates itself, and ignored entirely when an existing event is being edited.
        val prefill = Prefill(
            title = savedStateHandle.get<String>("title").orEmpty(),
            location = savedStateHandle.get<String>("location").orEmpty(),
            description = savedStateHandle.get<String>("description").orEmpty(),
            allDay = savedStateHandle.get<String>("allDay").toBoolean(),
        )
        load(eventId, startArg, endArg, calArg, prefill, copyFrom)
    }

    private data class Prefill(
        val title: String = "",
        val location: String = "",
        val description: String = "",
        val allDay: Boolean = false,
    )

    private fun load(
        eventId: Long,
        startArg: Long?,
        endArg: Long?,
        calArg: Long?,
        prefill: Prefill = Prefill(),
        copyFrom: Long = 0L,
    ) {
        viewModelScope.launch {
            val hidden = prefs.hiddenCalendarIds.first()
            globalReminderDefault = prefs.defaultReminderMinutes.first()
            calendarReminderDefaults = prefs.calendarReminderDefaults.first()
            val calendars = repository.getCalendars()
            val visible = calendars.filter { it.visible && it.id.toString() !in hidden }
            val recentLocations = repository.getRecentLocations()
            val mapsEnabled = prefs.osmMapsEnabled.first()
            if (eventId > 0L) {
                // For a recurring event, many instances share the same id; startArg carries the
                // begin time of the specific occurrence the user tapped so we edit the right one.
                val event = repository.getEventOccurrence(eventId, startArg ?: 0L)
                val reminders = repository.getReminderMinutes(eventId)
                val attendees = repository.getAttendees(eventId)
                if (event != null) {
                    val cal = event.calendarId
                    val startZ = event.start.atZone(zone)
                    val endZ = event.end.atZone(zone)
                    val spec = RecurrenceRules.parse(event.rrule, zone)
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
                        // The provider stores an all-day END as exclusive UTC midnight of the day
                        // *after* the last covered day, and save() already adds that day back on.
                        // Reading the raw value straight into the field therefore showed a one-day
                        // event as spanning two — and, worse, every save pushed the end a day out.
                        startDate = if (event.allDay) event.startLocalDate(zone) else startZ.toLocalDate(),
                        startTime = if (event.allDay) LocalTime.MIDNIGHT else startZ.toLocalTime(),
                        endDate = if (event.allDay) event.lastLocalDate(zone) else endZ.toLocalDate(),
                        endTime = if (event.allDay) LocalTime.MIDNIGHT else endZ.toLocalTime(),
                        location = event.location.orEmpty(),
                        recentLocations = recentLocations,
                        mapsEnabled = mapsEnabled,
                        description = event.description.orEmpty(),
                        frequency = spec.frequency,
                        interval = spec.interval,
                        recurrenceEndDate = spec.until,
                        recurrenceCount = spec.count,
                        byWeekday = spec.byWeekday,
                        // Expand the custom panel when the loaded rule actually uses the extra knobs
                        // so the user sees the real interval/end/by-weekday rather than a bare chip.
                        showCustomRecurrence = spec.isCustom,
                        reminderMinutes = reminders.distinct().sorted(),
                        attendees = attendees,
                        color = repository.getEventColor(eventId),
                        originalTimezone = event.timezone,
                    )
                    return@launch
                }
            }
            // Duplicating. Everything the user can see comes across, the recurrence rule verbatim
            // included, so a copy is actually a copy — except the guest list, which is deliberately
            // left behind: saving attendees is a scheduling message, and nobody duplicating a
            // meeting has asked to invite the room a second time. The title arrives selected under
            // an open keyboard, which is the first thing a copy usually needs changed.
            if (copyFrom > 0L) {
                val source = repository.getEventOccurrence(copyFrom, startArg ?: 0L)
                if (source != null) {
                    val startZ = source.start.atZone(zone)
                    val endZ = source.end.atZone(zone)
                    val spec = RecurrenceRules.parse(source.rrule, zone)
                    _state.value = EditorUiState(
                        loading = false,
                        isEditing = false,
                        title = source.title,
                        availableCalendars = visible,
                        // The source's calendar, unless it is one the user has since hidden —
                        // a copy must not be parked on a calendar the picker cannot even show.
                        selectedCalendarId = source.calendarId
                            .takeIf { id -> visible.any { it.id == id } }
                            ?: visible.firstOrNull()?.id,
                        allDay = source.allDay,
                        startDate = if (source.allDay) {
                            source.startLocalDate(zone)
                        } else {
                            startZ.toLocalDate()
                        },
                        startTime = if (source.allDay) LocalTime.MIDNIGHT else startZ.toLocalTime(),
                        endDate = if (source.allDay) source.lastLocalDate(zone) else endZ.toLocalDate(),
                        endTime = if (source.allDay) LocalTime.MIDNIGHT else endZ.toLocalTime(),
                        location = source.location.orEmpty(),
                        recentLocations = recentLocations,
                        mapsEnabled = mapsEnabled,
                        description = source.description.orEmpty(),
                        frequency = spec.frequency,
                        interval = spec.interval,
                        recurrenceEndDate = spec.until,
                        recurrenceCount = spec.count,
                        byWeekday = spec.byWeekday,
                        showCustomRecurrence = spec.isCustom,
                        // Carried so save() writes the source's rule as it stands rather than the
                        // approximation the controls can express; touching any of them still
                        // rebuilds, exactly as it does when editing.
                        originalRrule = source.rrule,
                        reminderMinutes = repository.getReminderMinutes(copyFrom)
                            .distinct()
                            .sorted(),
                        // The copied reminders are a choice already made; switching calendars must
                        // not quietly replace them with that calendar's default.
                        remindersTouched = true,
                        color = repository.getEventColor(copyFrom),
                    )
                    return@launch
                }
            }
            // new event
            val defaultStart = startArg?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?: nextHourFromNow()
            // Only when the caller did not say: a `+` on a specific slot already knows the length
            // the user dragged out, and the preference is the answer for the case with no slot.
            val defaultEnd = endArg?.let { Instant.ofEpochMilli(it).atZone(zone) }
                ?: defaultStart.plusMinutes(prefs.defaultEventMinutes.first().toLong())
            // The caller wins, then the calendar the user nominated, then whatever is first.
            // The nominated one is checked against the visible list because accounts get removed
            // and calendars get hidden, and a preference pointing at neither must not strand new
            // events on a calendar that is no longer there.
            val preferred = prefs.defaultCalendarId.first()?.takeIf { id ->
                visible.any { it.id == id }
            }
            val defaultCalendar = calArg ?: preferred ?: visible.firstOrNull()?.id
            _state.value = EditorUiState(
                loading = false,
                isEditing = false,
                availableCalendars = visible,
                selectedCalendarId = defaultCalendar,
                title = prefill.title,
                allDay = prefill.allDay,
                startDate = defaultStart.toLocalDate(),
                // An all-day event has no time of day, and showing the clock at whatever hour the
                // sender happened to stamp on it invites the user to save a time that is discarded.
                startTime = if (prefill.allDay) LocalTime.MIDNIGHT else defaultStart.toLocalTime(),
                endDate = defaultEnd.toLocalDate(),
                endTime = if (prefill.allDay) LocalTime.MIDNIGHT else defaultEnd.toLocalTime(),
                location = prefill.location,
                recentLocations = recentLocations,
                mapsEnabled = mapsEnabled,
                description = prefill.description,
                reminderMinutes = listOfNotNull(defaultReminderFor(defaultCalendar)),
            )
        }
    }

    private fun nextHourFromNow() =
        java.time.ZonedDateTime.now(zone)
            .plusHours(1)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

    /** [color] of null puts the event back on its calendar's colour. */
    fun updateColor(color: Int?) = mutate { it.copy(color = color) }

    fun updateTitle(value: String) = mutate { it.copy(title = value) }
    fun updateLocation(value: String) = mutate { it.copy(location = value) }
    fun updateDescription(value: String) = mutate { it.copy(description = value) }
    fun updateAllDay(value: Boolean) = mutate { it.copy(allDay = value) }
    fun updateStartDate(date: LocalDate) = mutate { it.copy(startDate = date).dragEndToStart() }
    fun updateStartTime(time: LocalTime) = mutate { it.copy(startTime = time).dragEndToStart() }
    fun updateEndDate(date: LocalDate) = mutate { it.copy(endDate = date).dragStartToEnd() }
    fun updateEndTime(time: LocalTime) = mutate { it.copy(endTime = time).dragStartToEnd() }
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
    /** Adds or removes one reminder. Passing null clears them all ("None"). */
    fun toggleReminder(minutes: Int?) = mutate { current ->
        if (minutes == null) {
            current.copy(reminderMinutes = emptyList(), remindersTouched = true)
        } else {
            val next = if (minutes in current.reminderMinutes) {
                current.reminderMinutes - minutes
            } else {
                current.reminderMinutes + minutes
            }
            current.copy(reminderMinutes = next.sorted(), remindersTouched = true)
        }
    }
    fun selectCalendar(id: Long) = mutate { current ->
        val next = current.copy(selectedCalendarId = id)
        // Only for a new event whose reminders the user has not chosen: on an existing event the
        // reminders are the event's own, and re-deriving them from the calendar would quietly
        // rewrite data the user never asked to change.
        if (current.isEditing || current.remindersTouched) {
            next
        } else {
            next.copy(reminderMinutes = listOfNotNull(defaultReminderFor(id)))
        }
    }

    private fun defaultReminderFor(calendarId: Long?): Int? = CalendarReminderDefaults.resolve(
        calendarId = calendarId,
        perCalendar = calendarReminderDefaults,
        global = globalReminderDefault,
    )

    fun updateGuestDraft(value: String) = mutate { it.copy(guestDraft = value) }

    /** Commits the draft address as a guest. No-op unless it is a new, well-formed one. */
    fun addGuest() = mutate { current ->
        if (!current.canAddGuest) return@mutate current
        current.copy(
            attendees = current.attendees + Attendee(email = current.guestDraft.trim()),
            guestDraft = "",
        )
    }

    /**
     * Removes one guest.
     *
     * The organizer is deliberately not removable: they are the event's owner in both RFC 5545 and
     * the provider, and dropping that row does not un-invite anyone — it just loses which of the
     * remaining addresses the invitation came from.
     */
    fun removeGuest(email: String) = mutate { current ->
        if (!current.canEditGuests) return@mutate current
        current.copy(
            attendees = current.attendees.filterNot {
                !it.isOrganizer &&
                    Attendee.normalizeAddress(it.email) == Attendee.normalizeAddress(email)
            },
        )
    }

    fun save() {
        // An address typed into the guest field but never committed is still one the user meant to
        // invite: tapping Save straight from the field is the obvious flow, and it does not go
        // through the field's own Done action. Commit it before reading the state.
        addGuest()
        val current = _state.value
        if (!current.canSave) return
        // Editing one occurrence of a series: ask whether to change just it or the whole series.
        if (current.isEditing && current.isRecurring) {
            mutate { it.copy(scopePrompt = RecurrenceScopePrompt.SAVE) }
            return
        }
        performSave(RecurrenceScope.ALL_EVENTS)
    }

    fun delete() {
        val current = _state.value
        if (!current.isEditing || current.eventId == 0L) return
        if (current.isRecurring) {
            mutate { it.copy(scopePrompt = RecurrenceScopePrompt.DELETE) }
            return
        }
        performDelete(RecurrenceScope.ALL_EVENTS)
    }

    fun dismissScopePrompt() = mutate { it.copy(scopePrompt = null) }

    /** Resolves a recurrence scope prompt by applying the edit/delete at the chosen scope. */
    fun resolveScope(scope: RecurrenceScope) {
        val prompt = _state.value.scopePrompt ?: return
        mutate { it.copy(scopePrompt = null) }
        when (prompt) {
            RecurrenceScopePrompt.SAVE -> performSave(scope)
            RecurrenceScopePrompt.DELETE -> performDelete(scope)
        }
    }

    private fun performSave(scope: RecurrenceScope) {
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
                // Keep the event anchored to the zone it was authored in. Rewriting it to the
                // device zone preserves the instant the user picked but re-anchors future
                // occurrences and shifts the event for every other client on the same CalDAV
                // calendar. New events (and all-day events, which are UTC by contract) fall back.
                timezone = when {
                    // "UTC", not ZoneOffset.UTC.id, which is "Z"; see Ics.UTC.
                    current.allDay -> "UTC"
                    else -> resolveEventTimezone(current.originalTimezone, zone)
                },
                frequency = current.frequency,
                rrule = rrule,
                reminderMinutes = current.reminderMinutes,
                // The editor loaded the full guest list, so it may write the full guest list —
                // including an empty one, which is how removing the last guest takes effect. On
                // an event the user did not organize, null instead: writing the list back even
                // unchanged re-sends it to the server, and it is not ours to re-send.
                attendees = current.attendees.takeIf { current.canEditGuests },
                color = current.color,
            )
            val saved = when {
                !current.isEditing -> repository.createEvent(input) != null
                current.isRecurring && scope == RecurrenceScope.SINGLE ->
                    repository.updateEventInstance(current.eventId, current.originalInstanceTime, input)
                current.isRecurring && scope == RecurrenceScope.THIS_AND_FOLLOWING ->
                    repository.updateEventFollowing(
                        current.eventId,
                        current.originalInstanceTime,
                        input,
                        rebaseCount = !current.recurrenceDirty,
                    )
                else -> repository.updateEvent(current.eventId, input)
            }
            // A refused write keeps the editor open with everything the user typed. Closing it
            // looked exactly like a save that worked.
            mutate { it.copy(saving = false, finished = saved) }
            if (!saved) messages.post("Couldn't save the event")
        }
    }

    /** Hands the delete to [PendingDeletes], which writes it once the Undo has gone unused. */
    private fun performDelete(scope: RecurrenceScope) {
        val current = _state.value
        pendingDeletes.request(
            eventId = current.eventId,
            instanceStartMillis = current.originalInstanceTime,
            scope = if (current.isRecurring) scope else RecurrenceScope.ALL_EVENTS,
            title = current.title.ifBlank { "(Untitled)" },
        )
        mutate { it.copy(finished = true, deleted = true) }
    }

    private fun combineInstant(date: LocalDate, time: LocalTime, allDay: Boolean): Instant {
        return if (allDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant()
        } else {
            date.atTime(time).atZone(zone).toInstant()
        }
    }

    private fun mutate(transform: (EditorUiState) -> EditorUiState) = _state.update(transform)
}

/**
 * Whether the state currently describes an event that ends before it begins. All-day events
 * compare by date alone — their times are pinned to midnight and carry no meaning.
 */
private fun EditorUiState.isInverted(): Boolean =
    if (allDay) endDate.isBefore(startDate)
    else endDate.atTime(endTime).isBefore(startDate.atTime(startTime))

/**
 * Nothing downstream rejects an event that ends before it starts: the provider stores it, the
 * timeline lays it out with a negative height, and `formatDuration` hands the recurring path a
 * negative DURATION. So moving one endpoint past the other drags the other along instead of
 * letting the pair go inverted.
 */
private fun EditorUiState.dragEndToStart(): EditorUiState =
    if (isInverted()) copy(endDate = startDate, endTime = startTime) else this

private fun EditorUiState.dragStartToEnd(): EditorUiState =
    if (isInverted()) copy(startDate = endDate, startTime = endTime) else this
