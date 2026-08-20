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
 * "This event, this and following, or all of them?" — asked wherever a series can be changed.
 *
 * One dialog rather than a copy per caller. There are three places that have to ask (saving an
 * edit, deleting, and dropping an occurrence somewhere else on the grid) and the answer means the
 * same thing in all three, so the wording should not drift between them. [verb] is what the user
 * is about to do, in the imperative, because "Delete this event" reads as a button and "Apply your
 * change to this event" does not.
 */
@Composable
fun RecurrenceScopeDialog(
    verb: String,
    onScope: (RecurrenceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val choose: (RecurrenceScope) -> Unit = { scope ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onScope(scope)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb recurring event") },
        text = {
            Column {
                Text("This event repeats. Apply your change to:")
                Spacer(Modifier.height(16.dp))
                ScopeChoice("$verb this event") { choose(RecurrenceScope.SINGLE) }
                ScopeChoice("$verb this and following events") {
                    choose(RecurrenceScope.THIS_AND_FOLLOWING)
                }
                ScopeChoice("$verb all events") { choose(RecurrenceScope.ALL_EVENTS) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ScopeChoice(label: String, onClick: () -> Unit) {
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
