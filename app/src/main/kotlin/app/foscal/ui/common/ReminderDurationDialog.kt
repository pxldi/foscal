package app.foscal.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.foscal.core.model.ReminderDuration
import app.foscal.core.model.ReminderUnit

/**
 * Picks an arbitrary reminder offset.
 *
 * The preset chips cover the common cases but not "40 minutes before, because that is how long the
 * commute takes" or "three weeks before the visa expires" — and until now those were unreachable
 * from inside Foscal, so an event needing one had to be edited in another calendar app.
 *
 * Amount plus unit rather than a raw minute count: the provider stores minutes, but nobody thinks
 * of a three-week reminder as 30240 of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDurationDialog(
    initialMinutes: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // Split the incoming value so re-opening on a 2-hour reminder shows "2 hours", not "120 minutes".
    val initial = remember(initialMinutes) {
        ReminderDuration.split(initialMinutes ?: DEFAULT_MINUTES)
    }
    var amount by rememberSaveable(initialMinutes) { mutableStateOf(initial.first.toString()) }
    // Stored by name rather than as the enum itself: rememberSaveable puts values in a Bundle, and
    // a plain String needs no custom Saver to survive a rotation or a process death.
    var unitName by rememberSaveable(initialMinutes) { mutableStateOf(initial.second.name) }
    val unit = ReminderUnit.valueOf(unitName)

    val parsed = amount.trim().toIntOrNull()
    val minutes = parsed?.let { ReminderDuration.toMinutes(it, unit) }
    // A blank field is "still typing", not an error; only a value that cannot become a reminder is
    // called out.
    val error = if (amount.isNotBlank() && minutes == null) {
        "Choose at most ${ReminderDuration.label(ReminderDuration.MAX_MINUTES)}"
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { new ->
                        // Digits only. The number keyboard is a hint, not a guarantee — hardware
                        // keyboards and several IMEs will happily send "-" or "1e5".
                        if (new.length <= MAX_DIGITS && new.all(Char::isDigit)) amount = new
                    },
                    label = { Text("Amount") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReminderUnit.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == unit,
                            onClick = { unitName = option.name },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                ReminderUnit.entries.size,
                            ),
                        ) {
                            Text(option.label(parsed ?: 2).replaceFirstChar(Char::uppercase))
                        }
                    }
                }
                Text(
                    // Always occupies a line so switching between valid and invalid input does not
                    // make the dialog jump.
                    text = error ?: minutes?.let { "Reminds ${ReminderDuration.label(it)} before" }
                        ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let(onConfirm) },
                enabled = minutes != null,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DEFAULT_MINUTES = 45

/** Five digits is past every accepted value, so longer input can only be a mistake. */
private const val MAX_DIGITS = 5
