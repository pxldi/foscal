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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foscal.core.model.DayTapAction
import app.foscal.core.model.ReminderDuration
import app.foscal.ui.home.BehaviourState
import app.foscal.ui.home.BehaviourViewModel
import app.foscal.ui.home.CalendarView
import app.foscal.ui.util.currentLocale
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * The settings that decide how the calendar behaves rather than how it looks.
 *
 * Deliberately short. Every one of these is something people genuinely differ on and the app has no
 * way to work out on its own — which view you live in, which day your week starts, how long your
 * meetings are. Anything we could reasonably decide ourselves stays decided, and nothing here
 * appears during onboarding: a first run should get you to your calendar, not to a questionnaire.
 */
@Composable
fun BehaviourSettings(state: BehaviourState, viewModel: BehaviourViewModel) {
    var picker by remember { mutableStateOf<BehaviourPicker?>(null) }

    when (picker) {
        BehaviourPicker.StartView -> ChoiceDialog(
            title = "Open on",
            options = startViewOptions(),
            selected = state.startView,
            onSelect = {
                viewModel.setStartView(it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        BehaviourPicker.FirstDay -> ChoiceDialog(
            title = "Week starts on",
            options = DayOfWeek.entries.map {
                it.name to it.getDisplayName(TextStyle.FULL, currentLocale())
            },
            selected = state.firstDayOfWeek.name,
            onSelect = {
                viewModel.setFirstDayOfWeek(DayOfWeek.valueOf(it))
                picker = null
            },
            onDismiss = { picker = null },
        )
        BehaviourPicker.EventLength -> ChoiceDialog(
            title = "New event length",
            options = EventLengths.map { it.toString() to ReminderDuration.label(it) },
            selected = state.defaultEventMinutes.toString(),
            onSelect = {
                viewModel.setDefaultEventMinutes(it.toInt())
                picker = null
            },
            onDismiss = { picker = null },
        )
        BehaviourPicker.DayTap -> ChoiceDialog(
            title = "Tapping a day header",
            options = listOf(
                DayTapAction.OPEN_DAY.name to "Open that day",
                DayTapAction.NEW_EVENT.name to "Start a new event",
            ),
            selected = state.dayTapAction.name,
            onSelect = {
                viewModel.setDayTapAction(DayTapAction.valueOf(it))
                picker = null
            },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    ValueRow(
        title = "Open on",
        value = startViewOptions().firstOrNull { it.first == state.startView }?.second
            ?: "Last used",
        onClick = { picker = BehaviourPicker.StartView },
    )
    ValueRow(
        title = "Week starts on",
        value = state.firstDayOfWeek.getDisplayName(TextStyle.FULL, currentLocale()),
        onClick = { picker = BehaviourPicker.FirstDay },
    )
    ValueRow(
        title = "New event length",
        value = ReminderDuration.label(state.defaultEventMinutes),
        onClick = { picker = BehaviourPicker.EventLength },
    )
    ValueRow(
        title = "Tapping a day header",
        value = when (state.dayTapAction) {
            DayTapAction.OPEN_DAY -> "Opens that day"
            DayTapAction.NEW_EVENT -> "Starts a new event"
        },
        onClick = { picker = BehaviourPicker.DayTap },
    )
    ToggleRow(
        title = "Week numbers",
        subtitle = if (state.showWeekNumbers) "Shown in Month" else "Hidden",
        checked = state.showWeekNumbers,
        onToggle = viewModel::setShowWeekNumbers,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

private enum class BehaviourPicker { StartView, FirstDay, EventLength, DayTap }

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
