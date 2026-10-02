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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foscal.R
import app.foscal.core.model.DayTapAction
import app.foscal.ui.calendars.CalendarRow
import app.foscal.ui.home.BehaviourState
import app.foscal.ui.home.BehaviourViewModel
import app.foscal.ui.home.CalendarView
import app.foscal.ui.util.asString
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.reminderLabel
import app.foscal.ui.util.timeFormatter
import java.time.DayOfWeek
import java.time.LocalTime
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
            title = stringResource(R.string.settings_opens_on),
            options = startViewOptions(),
            selected = state.startView,
            onSelect = { viewModel.setStartView(it); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.FirstDay -> ChoiceDialog(
            title = stringResource(R.string.settings_week_starts_on),
            options = DayOfWeek.entries.map {
                it.name to it.getDisplayName(TextStyle.FULL, currentLocale())
            },
            selected = state.firstDayOfWeek.name,
            onSelect = { viewModel.setFirstDayOfWeek(DayOfWeek.valueOf(it)); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.MonthMinimum -> ChoiceDialog(
            title = stringResource(R.string.settings_skip_short),
            options = MonthMinimumOptions.map { it.toString() to monthMinimumLabel(it) },
            selected = state.monthMinimumMinutes.toString(),
            onSelect = { viewModel.setMonthMinimumMinutes(it.toInt()); picker = null },
            onDismiss = { picker = null },
        )
        ViewPicker.DayTap -> ChoiceDialog(
            title = stringResource(R.string.settings_tapping_day),
            options = listOf(
                DayTapAction.OPEN_DAY.name to stringResource(R.string.settings_day_tap_open),
                DayTapAction.NEW_EVENT.name to stringResource(R.string.settings_day_tap_new),
            ),
            selected = state.dayTapAction.name,
            onSelect = { viewModel.setDayTapAction(DayTapAction.valueOf(it)); picker = null },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    Column {
        ValueRow(
            title = stringResource(R.string.settings_opens_on),
            value = startViewOptions().firstOrNull { it.first == state.startView }?.second
                ?: stringResource(R.string.settings_last_used),
            onClick = { picker = ViewPicker.StartView },
        )
        ValueRow(
            title = stringResource(R.string.settings_week_starts_on),
            value = state.firstDayOfWeek.getDisplayName(TextStyle.FULL, currentLocale()),
            onClick = { picker = ViewPicker.FirstDay },
        )
        ValueRow(
            title = stringResource(R.string.settings_tapping_day),
            value = when (state.dayTapAction) {
                DayTapAction.OPEN_DAY -> stringResource(R.string.settings_day_tap_opens)
                DayTapAction.NEW_EVENT -> stringResource(R.string.settings_day_tap_starts)
            },
            onClick = { picker = ViewPicker.DayTap },
        )
        ValueRow(
            title = stringResource(R.string.settings_skip_short),
            value = MonthMinimumOptions.firstOrNull { it == state.monthMinimumMinutes }
                ?.let { monthMinimumLabel(it) }
                ?: stringResource(R.string.settings_show_all),
            onClick = { picker = ViewPicker.MonthMinimum },
        )
        ToggleRow(
            title = stringResource(R.string.settings_week_numbers),
            subtitle = if (state.showWeekNumbers) {
                stringResource(R.string.settings_week_numbers_shown)
            } else {
                stringResource(R.string.settings_hidden)
            },
            checked = state.showWeekNumbers,
            onToggle = viewModel::setShowWeekNumbers,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        ToggleRow(
            title = stringResource(R.string.settings_24_hour),
            // An example time in the chosen form, so the subtitle follows the locale's AM/PM.
            subtitle = SampleTime.format(timeFormatter(use24HourClock, currentLocale())),
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
    val options = listOf("" to stringResource(R.string.settings_first_available)) +
        calendars.filter { !it.isHidden && it.calendar.isWritable }
            .map { it.calendar.id.toString() to it.calendar.displayName }

    when (picker) {
        EventPicker.Calendar -> ChoiceDialog(
            title = stringResource(R.string.settings_default_calendar),
            options = options,
            selected = state.defaultCalendarId?.toString() ?: "",
            onSelect = { viewModel.setDefaultCalendarId(it.toLongOrNull()); picker = null },
            onDismiss = { picker = null },
        )
        EventPicker.Length -> ChoiceDialog(
            title = stringResource(R.string.settings_length),
            options = EventLengths.map { it.toString() to reminderLabel(it).asString() },
            selected = state.defaultEventMinutes.toString(),
            onSelect = { viewModel.setDefaultEventMinutes(it.toInt()); picker = null },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    Column {
        ValueRow(
            title = stringResource(R.string.settings_default_calendar),
            value = options.firstOrNull { it.first == (state.defaultCalendarId?.toString() ?: "") }
                ?.second ?: stringResource(R.string.settings_first_available),
            onClick = { picker = EventPicker.Calendar },
        )
        ValueRow(
            title = stringResource(R.string.settings_length),
            value = reminderLabel(state.defaultEventMinutes).asString(),
            onClick = { picker = EventPicker.Length },
        )
        ToggleRow(
            title = stringResource(R.string.settings_maps),
            subtitle = stringResource(
                if (mapsEnabled) R.string.settings_maps_on else R.string.settings_maps_off,
            ),
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
private val MonthMinimumOptions = listOf(0, 30, 60, 120)

@Composable
private fun monthMinimumLabel(minutes: Int): String =
    if (minutes == 0) {
        stringResource(R.string.settings_show_all)
    } else {
        stringResource(R.string.settings_skip_under, reminderLabel(minutes).asString())
    }

private val SampleTime: LocalTime = LocalTime.of(13, 0)

private enum class EventPicker { Calendar, Length }

/** Lengths worth offering. Anything else is a drag on the grid away. */
private val EventLengths = listOf(15, 30, 45, 60, 90, 120)

/** "Last used" first, because it is the default and the answer most people never change. */
@Composable
private fun startViewOptions(): List<Pair<String, String>> =
    listOf("" to stringResource(R.string.settings_last_used)) +
        CalendarView.entries.map { it.name to stringResource(it.labelRes) }

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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
