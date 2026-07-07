package app.calendarium.ui.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.model.AccentColor
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalendarsUiState(
    val items: List<CalendarRow> = emptyList(),
    val loading: Boolean = true,
    val defaultReminderMinutes: Int? = 15,
    val accentColor: AccentColor = AccentColor.Default,
    val themeMode: ThemeMode = ThemeMode.Default,
    val use24HourClock: Boolean = true,
)

data class CalendarRow(
    val calendar: Calendar,
    val isHidden: Boolean,
)

private data class PrefsSnapshot(
    val hidden: Set<String>,
    val defaultReminder: Int?,
    val accent: AccentColor,
    val themeMode: ThemeMode,
    val use24Hour: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val prefsFlow = combine(
        prefs.hiddenCalendarIds,
        prefs.defaultReminderMinutes,
        prefs.accentColor,
        prefs.themeMode,
        prefs.use24HourClock,
    ) { hidden, defaultReminder, accent, themeMode, use24Hour ->
        PrefsSnapshot(hidden, defaultReminder, accent, themeMode, use24Hour)
    }

    val state: StateFlow<CalendarsUiState> = combine(
        repository.observeCalendars(),
        prefsFlow,
    ) { all, p ->
        CalendarsUiState(
            items = all.map { cal ->
                CalendarRow(cal, isHidden = cal.id.toString() in p.hidden)
            },
            loading = false,
            defaultReminderMinutes = p.defaultReminder,
            accentColor = p.accent,
            themeMode = p.themeMode,
            use24HourClock = p.use24Hour,
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

    fun setAccentColor(accent: AccentColor) {
        viewModelScope.launch { prefs.setAccentColor(accent) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setUse24HourClock(use24Hour: Boolean) {
        viewModelScope.launch { prefs.setUse24HourClock(use24Hour) }
    }
}
