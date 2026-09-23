package app.foscal.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.foscal.core.model.Calendar

/**
 * Asks which calendar an incoming `.ics` file should be added to, then reports what happened.
 *
 * The Settings version of this picks the calendar *before* opening a document picker; here the
 * file arrived first, so the same question is asked afterwards and the outcome is shown in place
 * rather than on a settings page the user never opened.
 */
@Composable
fun ImportIcsDialog(
    calendars: List<Calendar>,
    transfer: TransferState,
    onImport: (calendarId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val message = transfer.message
    // A read-only calendar refuses the inserts, so it is not offered.
    val targets = calendars.filter { it.isWritable }
    AlertDialog(
        onDismissRequest = { if (!transfer.busy) onDismiss() },
        title = { Text(if (message == null) "Import into" else "Import") },
        text = {
            when {
                message != null -> Text(
                    message,
                    color = if (transfer.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                transfer.busy -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Working…")
                }

                targets.isEmpty() -> Text("There is no calendar to import into yet.")

                // Scrollable because this list is however many calendars the phone has, and an
                // incoming file is exactly when the user cannot go and tidy them up first.
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onImport(calendar.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(calendar.color)),
                            )
                            Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Nothing to confirm while the list is up: tapping a calendar is the action.
            TextButton(
                onClick = onDismiss,
                enabled = !transfer.busy,
            ) {
                Text(if (message == null) "Cancel" else "Done")
            }
        },
    )
}
