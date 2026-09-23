package app.foscal.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foscal.core.model.Calendar

/**
 * Which calendars to write to the file.
 *
 * A list of checkboxes rather than a choice between "one" and "everything": those are the two
 * common answers, but they are the ends of the same question, and a picker that can express both
 * can also express the third case — a couple of calendars out of nine — without a mode switch.
 * It opens on whatever is visible in the app, which is what the button used to do on its own.
 *
 * The counts are the point of the screen. "Export" with no idea what is in the file is how you
 * find out afterwards that the calendar you meant was the empty one.
 */
@Composable
fun ExportPickerDialog(
    calendars: List<Calendar>,
    counts: Map<Long, Int>,
    initialSelection: Set<Long>,
    onDismiss: () -> Unit,
    onExport: (Set<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelection) }
    val haptics = LocalHapticFeedback.current
    val total = selected.sumOf { counts[it] ?: 0 }
    val known = counts.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export to .ics") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selected = calendars.map { it.id }.toSet() }) {
                        Text("All")
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text("None") }
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    calendars.forEach { calendar ->
                        val checked = calendar.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptics.performHapticFeedback(
                                        if (checked) {
                                            HapticFeedbackType.ToggleOff
                                        } else {
                                            HapticFeedbackType.ToggleOn
                                        },
                                    )
                                    selected = if (checked) {
                                        selected - calendar.id
                                    } else {
                                        selected + calendar.id
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            ColorDot(calendar.color)
                            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                Text(
                                    calendar.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (known) {
                                    val n = counts[calendar.id] ?: 0
                                    Text(
                                        if (n == 1) "1 event" else "$n events",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(
                    when {
                        selected.isEmpty() -> "Export"
                        !known -> "Export"
                        else -> "Export $total ${if (total == 1) "event" else "events"}"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Which calendar a file's events should land on — including one that does not exist yet.
 *
 * "Somewhere of its own" is the usual answer for a file from another app: a shared calendar, an
 * old export, somebody's itinerary. Sending the user out to Calendars to make one first and then
 * back here to start again is three screens for a decision they had already made, so the row is
 * in the list with the others and turns this dialog into the new-calendar form in place.
 */
@Composable
fun ImportTargetDialog(
    calendars: List<Calendar>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import into") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // A read-only calendar refuses the inserts, so it is not offered.
                calendars.filter { it.isWritable }.forEach { calendar ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(calendar.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ColorDot(calendar.color)
                        Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onCreate)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "New calendar…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
