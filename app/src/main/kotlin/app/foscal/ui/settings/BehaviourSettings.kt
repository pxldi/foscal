package app.foscal.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foscal.core.model.DayTapAction
import app.foscal.core.model.ReminderDuration
import app.foscal.ui.calendars.CalendarRow
import app.foscal.ui.home.BehaviourState
import app.foscal.ui.home.BehaviourViewModel
import app.foscal.ui.home.CalendarView
import app.foscal.ui.util.currentLocale
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * How the calendar reads: which view it opens on, where a week begins, what a tap does.
 *
 * Deliberately short. Every one of these is something people genuinely differ on and the app has
 * no way to work out on its own. Anything we could reasonably decide ourselves stays decided, and
 * none of it appears during onboarding: a first run should get you to your calendar.
 */
@Composable
fun CalendarViewSettings(
    state: BehaviourState,
    viewModel: BehaviourViewModel,
    use24HourClock: Boolean,
    onUse24HourClock: (Boolean) -> Unit,
) {
    var picker by remember { mutableStateOf<ViewPicker?>(null) }

    when (picker) {
        ViewPicker.StartView -> ChoiceDialog(
            title = "Opens on",
            options = startViewOptions(),
            selected = state.startView,
            onSelect = { viewModel.setStartView(it); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.FirstDay -> ChoiceDialog(
            title = "Week starts on",
            options = DayOfWeek.entries.map {
                it.name to it.getDisplayName(TextStyle.FULL, currentLocale())
            },
            selected = state.firstDayOfWeek.name,
            onSelect = { viewModel.setFirstDayOfWeek(DayOfWeek.valueOf(it)); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.MonthMinimum -> ChoiceDialog(
            title = "Skip short events in Month",
            options = MonthMinimumOptions.map { (minutes, label) -> minutes.toString() to label },
            selected = state.monthMinimumMinutes.toString(),
            onSelect = { viewModel.setMonthMinimumMinutes(it.toInt()); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.DayTap -> ChoiceDialog(
            title = "Tapping a day",
            options = listOf(
                DayTapAction.OPEN_DAY.name to "Open that day",
                DayTapAction.NEW_EVENT.name to "Start a new event",
            ),
            selected = state.dayTapAction.name,
            onSelect = { viewModel.setDayTapAction(DayTapAction.valueOf(it)); picker = null },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    Column {
        ValueRow(
            title = "Opens on",
            value = startViewOptions().firstOrNull { it.first == state.startView }?.second
                ?: "Last used",
            onClick = { picker = ViewPicker.StartView },
        )
        ValueRow(
            title = "Week starts on",
            value = state.firstDayOfWeek.getDisplayName(TextStyle.FULL, currentLocale()),
            onClick = { picker = ViewPicker.FirstDay },
        )
        ValueRow(
            title = "Tapping a day",
            value = when (state.dayTapAction) {
                DayTapAction.OPEN_DAY -> "Opens that day"
                DayTapAction.NEW_EVENT -> "Starts a new event"
            },
            onClick = { picker = ViewPicker.DayTap },
        )
        ValueRow(
            title = "Skip short events in Month",
            value = MonthMinimumOptions.firstOrNull { it.first == state.monthMinimumMinutes }?.second
                ?: "Show all",
            onClick = { picker = ViewPicker.MonthMinimum },
        )
        ToggleRow(
            title = "Week numbers",
            subtitle = if (state.showWeekNumbers) "Shown in Month" else "Hidden",
            checked = state.showWeekNumbers,
            onToggle = viewModel::setShowWeekNumbers,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        ToggleRow(
            title = "24-hour time",
            subtitle = if (use24HourClock) "13:00" else "1:00 PM",
            checked = use24HourClock,
            onToggle = onUse24HourClock,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** What a new event starts out as, before you have typed anything into it. */
@Composable
fun NewEventSettings(
    state: BehaviourState,
    viewModel: BehaviourViewModel,
    calendars: List<CalendarRow>,
    mapsEnabled: Boolean,
    onMapsEnabled: (Boolean) -> Unit,
) {
    var picker by remember { mutableStateOf<EventPicker?>(null) }
    // Hidden calendars are left out: nominating one would put new events somewhere you cannot see
    // them. "First available" leads, because it is the default and needs no decision.
    val options = listOf("" to "First available") +
        calendars.filterNot { it.isHidden }.map { it.calendar.id.toString() to it.calendar.displayName }

    when (picker) {
        EventPicker.Calendar -> ChoiceDialog(
            title = "Default calendar",
            options = options,
            selected = state.defaultCalendarId?.toString() ?: "",
            onSelect = { viewModel.setDefaultCalendarId(it.toLongOrNull()); picker = null },
            onDismiss = { picker = null },
        )
        EventPicker.Length -> ChoiceDialog(
            title = "Length",
            options = EventLengths.map { it.toString() to ReminderDuration.label(it) },
            selected = state.defaultEventMinutes.toString(),
            onSelect = { viewModel.setDefaultEventMinutes(it.toInt()); picker = null },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    Column {
        ValueRow(
            title = "Default calendar",
            value = options.firstOrNull { it.first == (state.defaultCalendarId?.toString() ?: "") }
                ?.second ?: "First available",
            onClick = { picker = EventPicker.Calendar },
        )
        ValueRow(
            title = "Length",
            value = ReminderDuration.label(state.defaultEventMinutes),
            onClick = { picker = EventPicker.Length },
        )
        ToggleRow(
            title = "Pick locations on a map",
            subtitle = if (mapsEnabled) {
                "Searches OpenStreetMap"
            } else {
                "Type addresses by hand"
            },
            checked = mapsEnabled,
            onToggle = onMapsEnabled,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private enum class ViewPicker { StartView, FirstDay, DayTap, MonthMinimum }

/**
 * Thresholds for thinning the month grid.
 *
 * Coarse on purpose: the point is "stop drawing the ten-minute things", not a dial. All-day
 * events are never skipped whatever is chosen here — being all day is what earns a month cell.
 */
private val MonthMinimumOptions = listOf(
    0 to "Show all",
    30 to "Skip under 30 min",
    60 to "Skip under 1 hour",
    120 to "Skip under 2 hours",
)

private enum class EventPicker { Calendar, Length }

/** Lengths worth offering. Anything else is a drag on the grid away. */
private val EventLengths = listOf(15, 30, 45, 60, 90, 120)

/** "Last used" first, because it is the default and the answer most people never change. */
private fun startViewOptions(): List<Pair<String, String>> =
    listOf("" to "Last used") + CalendarView.entries.map { it.name to it.label }

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSelect(key) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = key == selected, onClick = { onSelect(key) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
