package app.foscal.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.foscal.ui.editor.RecurrenceScope

/**
 * Confirming a delete, and for a series, deciding how much of it. The detail screen and the
 * editor both delete, and ask the same question.
 *
 * A recurring event gets the three choices instead of a yes/no, because "delete" has no single
 * meaning for one: the dialog that asks how much to remove is also the one that asks whether to.
 */
@Composable
fun DeleteEventDialog(
    recurring: Boolean,
    onDelete: (RecurrenceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // The dialog closes and the screen leaves, so the event is gone before the eye has anywhere to
    // look. This is the one action in the app that cannot be taken back, and it is worth feeling.
    val confirm: (RecurrenceScope) -> Unit = { scope ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onDelete(scope)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (recurring) "Delete recurring event" else "Delete event?") },
        // A one-off needs no body: the title asks the question, and the snackbar that follows
        // offers Undo.
        text = if (recurring) {
            {
                Column {
                    Text("This event repeats. Delete:")
                    Spacer(Modifier.height(16.dp))
                    DeleteChoice("This event") { confirm(RecurrenceScope.SINGLE) }
                    DeleteChoice("This and following events") {
                        confirm(RecurrenceScope.THIS_AND_FOLLOWING)
                    }
                    DeleteChoice("All events") { confirm(RecurrenceScope.ALL_EVENTS) }
                }
            }
        } else {
            null
        },
        confirmButton = {
            if (recurring) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = { confirm(RecurrenceScope.ALL_EVENTS) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            if (!recurring) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeleteChoice(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
