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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
    private val icsTransfer: IcsTransfer,
) : ViewModel() {

    private val transferState = MutableStateFlow(TransferState())

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
            )
        },
        prefs.osmMapsEnabled,
        prefs.accentCustomColor,
        prefs.dynamicColor,
        prefs.calendarReminderDefaults,
    ) { snapshot, osmMaps, accentCustom, dynamicColor, calendarReminders ->
        snapshot.copy(
            osmMaps = osmMaps,
            accentCustom = accentCustom,
            dynamicColor = dynamicColor,
            calendarReminders = calendarReminders,
        )
    }

    val state: StateFlow<CalendarsUiState> = combine(
        repository.observeCalendars(),
        prefsFlow,
        transferState,
    ) { all, p, transfer ->
        CalendarsUiState(
            transfer = transfer,
            items = all.map { cal ->
                CalendarRow(
                    calendar = cal,
                    isHidden = cal.id.toString() in p.hidden,
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
    fun exportTo(target: Uri) {
        runTransfer {
            val calendarIds = state.value.items
                .filterNot { it.isHidden }
                .map { it.calendar.id }
                .toSet()
            if (calendarIds.isEmpty()) {
                TransferState(message = "No visible calendars to export", failed = true)
            } else {
                val count = icsTransfer.export(target, calendarIds)
                TransferState(message = "Exported $count ${plural(count, "event")}")
            }
        }
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
