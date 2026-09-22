package app.foscal.ui.calendars

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.UserPreferencesRepository
import app.foscal.core.model.AccentColor
import app.foscal.core.model.Calendar
import app.foscal.core.model.ThemeMode
import app.foscal.ics.IcsTransfer
import app.foscal.notifications.ReminderSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class CalendarsUiState(
    val items: List<CalendarRow> = emptyList(),
    val loading: Boolean = true,
    val defaultReminderMinutes: Int? = 15,
    val accentColor: AccentColor = AccentColor.Default,
    val accentCustomColor: Int = AccentColor.DEFAULT_CUSTOM_COLOR,
    val dynamicColor: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.Default,
    val use24HourClock: Boolean = true,
    val osmMapsEnabled: Boolean = false,
    val transfer: TransferState = TransferState(),
    /** Why the last attempt to add a calendar came to nothing, if it did. */
    val createError: String? = null,
    /** The calendar the user is being asked to confirm deleting, and what it would take with it. */
    val pendingDelete: PendingDelete? = null,
    /** The calendar open for renaming and recolouring, if one is. */
    val editing: EditingCalendar? = null,
    /**
     * How many events each calendar holds, for the export picker.
     *
     * Loaded when that picker opens rather than kept up to date: it is a count per calendar over
     * the whole of time, which is not a thing to be recomputing behind a screen nobody is looking
     * at. Empty until then, and the picker simply shows no counts.
     */
    val eventCounts: Map<Long, Int> = emptyMap(),
    /** A calendar just made from inside the import flow, waiting to be imported into. */
    val createdForImport: Long? = null,
)

/**
 * Everything that is open, being typed into, or waiting to be picked up.
 *
 * A holder rather than five more arguments to the outer `combine`, which is already at the arity
 * the overloads stop at.
 */
private data class Dialogs(
    val error: String?,
    val pendingDelete: PendingDelete?,
    val editing: EditingCalendar?,
    val eventCounts: Map<Long, Int>,
    val createdForImport: Long?,
)

/** A calendar open in the edit dialog, with the values it started from. */
data class EditingCalendar(
    val calendarId: Long,
    val name: String,
    val color: Int,
)

/** A delete waiting on confirmation. [eventCount] is read before asking, not after. */
data class PendingDelete(
    val calendarId: Long,
    val displayName: String,
    val eventCount: Int,
)

/** Progress and outcome of an `.ics` import or export, shown inline in Settings. */
data class TransferState(
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

data class CalendarRow(
    val calendar: Calendar,
    val isHidden: Boolean,
    /** Kept out of the month grid, while still showing in Day, Week and Agenda. */
    val isHiddenInMonth: Boolean = false,
    /**
     * The reminder this calendar pre-fills, and whether that is its own choice.
     *
     * Two fields rather than a nullable one because null is a real answer here — "None on this
     * calendar" — and it has to be distinguishable from "no override, follow the global default".
     */
    val reminderOverride: Int? = null,
    val usesGlobalReminder: Boolean = true,
)

private data class PrefsSnapshot(
    val hidden: Set<String>,
    val defaultReminder: Int?,
    val accent: AccentColor,
    val themeMode: ThemeMode,
    val use24Hour: Boolean,
    val osmMaps: Boolean,
    val accentCustom: Int,
    val dynamicColor: Boolean,
    val calendarReminders: Map<Long, Int?>,
    val monthHidden: Set<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
    private val icsTransfer: IcsTransfer,
    private val syncScheduler: ReminderSyncScheduler,
) : ViewModel() {

    private val transferState = MutableStateFlow(TransferState())
    private val createError = MutableStateFlow<String?>(null)
    private val eventCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val createdForImport = MutableStateFlow<Long?>(null)
    private val pendingDelete = MutableStateFlow<PendingDelete?>(null)
    private val editing = MutableStateFlow<EditingCalendar?>(null)

    // combine() has no typed 6+-arg overload, so fold the extra preferences in with a nested combine.
    private val prefsFlow = combine(
        combine(
            prefs.hiddenCalendarIds,
            prefs.defaultReminderMinutes,
            prefs.accentColor,
            prefs.themeMode,
            prefs.use24HourClock,
        ) { hidden, defaultReminder, accent, themeMode, use24Hour ->
            PrefsSnapshot(
                hidden = hidden,
                defaultReminder = defaultReminder,
                accent = accent,
                themeMode = themeMode,
                use24Hour = use24Hour,
                osmMaps = false,
                accentCustom = 0,
                dynamicColor = false,
                calendarReminders = emptyMap(),
                monthHidden = emptySet(),
            )
        },
        prefs.osmMapsEnabled,
        prefs.accentCustomColor,
        prefs.dynamicColor,
        // Paired because the outer combine is already at its five-argument overload.
        combine(prefs.calendarReminderDefaults, prefs.monthHiddenCalendarIds, ::Pair),
    ) { snapshot, osmMaps, accentCustom, dynamicColor, remindersAndMonth ->
        val (calendarReminders, monthHidden) = remindersAndMonth
        snapshot.copy(
            osmMaps = osmMaps,
            accentCustom = accentCustom,
            dynamicColor = dynamicColor,
            calendarReminders = calendarReminders,
            monthHidden = monthHidden,
        )
    }

    val state: StateFlow<CalendarsUiState> = combine(
        repository.observeCalendars(),
        prefsFlow,
        transferState,
        combine(createError, pendingDelete, editing, eventCounts, createdForImport, ::Dialogs),
    ) { all, p, transfer, dialogs ->
        val error = dialogs.error
        val delete = dialogs.pendingDelete
        val edit = dialogs.editing
        CalendarsUiState(
            transfer = transfer,
            createError = error,
            pendingDelete = delete,
            editing = edit,
            eventCounts = dialogs.eventCounts,
            createdForImport = dialogs.createdForImport,
            items = all.map { cal ->
                CalendarRow(
                    calendar = cal,
                    isHidden = cal.id.toString() in p.hidden,
                    isHiddenInMonth = cal.id.toString() in p.monthHidden,
                    reminderOverride = p.calendarReminders[cal.id],
                    usesGlobalReminder = !p.calendarReminders.containsKey(cal.id),
                )
            },
            loading = false,
            defaultReminderMinutes = p.defaultReminder,
            accentColor = p.accent,
            accentCustomColor = p.accentCustom,
            dynamicColor = p.dynamicColor,
            themeMode = p.themeMode,
            use24HourClock = p.use24Hour,
            osmMapsEnabled = p.osmMaps,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarsUiState(),
    )

    fun toggleHidden(row: CalendarRow) {
        viewModelScope.launch {
            val current = prefs.hiddenCalendarIds.first()
            val id = row.calendar.id.toString()
            val next = if (row.isHidden) current - id else current + id
            prefs.setHiddenCalendars(next)
            // The reminder sync skips hidden calendars, but this toggle lives in DataStore and
            // writes nothing to the provider, so no content trigger would ever re-run it.
            syncScheduler.syncNow()
        }
    }

    fun toggleHiddenInMonth(row: CalendarRow) {
        viewModelScope.launch {
            val current = prefs.monthHiddenCalendarIds.first()
            val id = row.calendar.id.toString()
            val next = if (row.isHiddenInMonth) current - id else current + id
            prefs.setMonthHiddenCalendars(next)
        }
    }

    /**
     * Adds a calendar on this device under [name], drawn in [color].
     *
     * Local only, because that is the only kind an app can create: a calendar belonging to a
     * Google or CalDAV account is created by whatever syncs that account, and one invented here
     * would never reach the server. Nothing is written to preferences — the new calendar arrives
     * through [CalendarRepository.observeCalendars] like any other, visible by default.
     */
    fun createCalendar(name: String, color: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        createError.value = null
        viewModelScope.launch {
            if (repository.createLocalCalendar(trimmed, color) == null) {
                createError.value = "Couldn't add the calendar."
            }
        }
    }

    fun dismissCreateError() {
        createError.value = null
    }

    /**
     * Asks the provider how much is about to be lost, then puts the question to the user.
     *
     * The count is read now rather than taken from the list already in hand: that list is what is
     * visible in the calendar, so hidden or filtered events would make it an undercount in exactly
     * the situation where the number is the whole point.
     */
    fun confirmDelete(calendarId: Long, displayName: String) {
        viewModelScope.launch {
            pendingDelete.value = PendingDelete(
                calendarId = calendarId,
                displayName = displayName,
                eventCount = repository.countEvents(calendarId),
            )
        }
    }

    fun dismissDelete() {
        pendingDelete.value = null
    }

    fun startEdit(calendarId: Long, name: String, color: Int) {
        createError.value = null
        editing.value = EditingCalendar(calendarId, name, color)
    }

    fun dismissEdit() {
        editing.value = null
        createError.value = null
    }

    fun saveCalendar(calendarId: Long, name: String, color: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        editing.value = null
        viewModelScope.launch {
            if (!repository.updateLocalCalendar(calendarId, trimmed, color)) {
                createError.value = "Couldn't change the calendar."
            }
        }
    }

    fun deleteCalendar(calendarId: Long) {
        pendingDelete.value = null
        viewModelScope.launch {
            if (!repository.deleteLocalCalendar(calendarId)) {
                createError.value = "Couldn't remove the calendar."
            }
        }
    }

    fun setDefaultReminder(minutes: Int?) {
        viewModelScope.launch { prefs.setDefaultReminder(minutes) }
    }

    /** Overrides one calendar's pre-filled reminder; [minutes] of null means "None here". */
    fun setCalendarReminder(calendarId: Long, minutes: Int?) {
        viewModelScope.launch { prefs.setCalendarReminderDefault(calendarId, minutes) }
    }

    /** Drops the override so this calendar follows the global default again. */
    fun clearCalendarReminder(calendarId: Long) {
        viewModelScope.launch { prefs.clearCalendarReminderDefault(calendarId) }
    }

    fun setAccentColor(accent: AccentColor) {
        viewModelScope.launch { prefs.setAccentColor(accent) }
    }

    /** Persists [color] as the custom accent seed and switches the accent to CUSTOM. */
    fun setCustomAccentColor(color: Int) {
        viewModelScope.launch {
            prefs.setAccentCustomColor(color)
            prefs.setAccentColor(AccentColor.CUSTOM)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setUse24HourClock(use24Hour: Boolean) {
        viewModelScope.launch { prefs.setUse24HourClock(use24Hour) }
    }

    fun setOsmMapsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setOsmMapsEnabled(enabled) }
    }

    /** Writes every event on the currently visible calendars to the document at [target]. */
    /** Counts every calendar's events, so the export picker can say what each one is worth. */
    fun loadEventCounts() {
        viewModelScope.launch {
            val counts = state.value.items.associate { it.calendar.id to repository.countEvents(it.calendar.id) }
            eventCounts.value = counts
        }
    }

    /**
     * Writes the events on [calendarIds] to [target].
     *
     * The caller says which. It used to be "every visible calendar", which is a reasonable default
     * and a poor only option: the common reasons to export are one calendar to hand to somebody
     * and all of them for a backup, and neither is served by a rule about what happens to be
     * on screen.
     */
    fun exportTo(target: Uri, calendarIds: Set<Long>) {
        runTransfer {
            if (calendarIds.isEmpty()) {
                TransferState(message = "No calendars selected", failed = true)
            } else {
                val count = icsTransfer.export(target, calendarIds)
                TransferState(message = "Exported $count ${plural(count, "event")}")
            }
        }
    }

    /**
     * Makes a calendar and holds on to its id, for importing a file into a calendar of its own.
     *
     * Separate from [createCalendar] because the id is the point: the import that follows has to
     * name a target, and the ordinary path throws the id away.
     */
    fun createCalendarForImport(name: String, color: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        createError.value = null
        viewModelScope.launch {
            val id = repository.createLocalCalendar(trimmed, color)
            if (id == null) {
                createError.value = "Couldn't add the calendar."
            } else {
                createdForImport.value = id
            }
        }
    }

    /** Called once the import flow has taken the new calendar's id and started on it. */
    fun consumeCreatedCalendar() {
        createdForImport.value = null
    }

    /** Creates every event in the document at [source] on [calendarId]. */
    fun importFrom(source: Uri, calendarId: Long) {
        runTransfer {
            val summary = icsTransfer.import(source, calendarId)
            val message = buildString {
                append("Imported ${summary.imported} ${plural(summary.imported, "event")}")
                if (summary.skipped > 0) append(" · ${summary.skipped} skipped")
            }
            TransferState(message = message, failed = summary.imported == 0)
        }
    }

    fun dismissTransferMessage() {
        transferState.value = TransferState()
    }

    /**
     * Runs [block] with the busy flag held, turning any failure into a message rather than a crash.
     * A document URI can go stale between the picker returning it and the read (the file is
     * deleted, the provider is uninstalled, a network volume drops), and the file itself is
     * arbitrary user input — none of that should take the app down.
     */
    private fun runTransfer(block: suspend () -> TransferState) {
        if (transferState.value.busy) return
        viewModelScope.launch {
            transferState.value = TransferState(busy = true)
            transferState.value = try {
                block()
            } catch (e: IOException) {
                TransferState(message = e.message ?: "Could not read the file", failed = true)
            } catch (_: SecurityException) {
                TransferState(message = "No longer allowed to access that file", failed = true)
            }
        }
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
}
