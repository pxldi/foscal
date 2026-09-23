package app.foscal.ui.editor

import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/**
 * The time picker, with Material's toggle between the dial and typed input. The dial alone made
 * 17:43 a fiddly target, and the keyboard is also the only way in for anyone who cannot drag.
 * The choice is remembered for the next time the picker opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerModal(
    initial: LocalTime,
    is24Hour: Boolean = true,
    onDismiss: () -> Unit,
    onSelect: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = is24Hour,
    )
    var displayMode by rememberSaveable(stateSaver = DisplayModeSaver) {
        mutableStateOf(lastDisplayMode)
    }
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                lastDisplayMode = displayMode
                onSelect(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { TimePickerDialogDefaults.Title(displayMode = displayMode) },
        modeToggleButton = {
            val toggle = {
                displayMode = if (displayMode == TimePickerDisplayMode.Picker) {
                    TimePickerDisplayMode.Input
                } else {
                    TimePickerDisplayMode.Picker
                }
            }
            // Material 1.4 labels the keyboard icon "Switch to clock mode", the opposite of what
            // it does, so TalkBack gets a label of our own.
            val label = if (displayMode == TimePickerDisplayMode.Picker) "Type the time" else "Use the dial"
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = toggle,
                displayMode = displayMode,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick(label) {
                        toggle()
                        true
                    }
                },
            )
        },
    ) {
        if (displayMode == TimePickerDisplayMode.Picker) TimePicker(state = state) else TimeInput(state = state)
    }
}

/** The mode the picker was last confirmed in, for this process. */
@OptIn(ExperimentalMaterial3Api::class)
private var lastDisplayMode: TimePickerDisplayMode = TimePickerDisplayMode.Picker

@OptIn(ExperimentalMaterial3Api::class)
private val DisplayModeSaver = Saver<TimePickerDisplayMode, Boolean>(
    save = { it == TimePickerDisplayMode.Input },
    restore = { if (it) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker },
)
