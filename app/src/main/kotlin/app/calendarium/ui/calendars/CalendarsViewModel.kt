package app.calendarium.ui.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.model.Calendar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CalendarsUiState(
    val items: List<CalendarRow> = emptyList(),
    val loading: Boolean = true,
    val defaultReminderMinutes: Int? = 15,
)

data class CalendarRow(
    val calendar: Calendar,
    val isHidden: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    val state: StateFlow<CalendarsUiState> = combine(
        repository.observeCalendars(),
        prefs.hiddenCalendarIds,
        prefs.defaultReminderMinutes,
    ) { all, hidden, defaultReminder ->
        CalendarsUiState(
            items = all.map { cal ->
                CalendarRow(cal, isHidden = cal.id.toString() in hidden)
            },
            loading = false,
            defaultReminderMinutes = defaultReminder,
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
}
