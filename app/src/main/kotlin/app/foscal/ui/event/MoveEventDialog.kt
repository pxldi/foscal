package app.foscal.ui.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foscal.R
import app.foscal.core.model.Event
import app.foscal.ui.common.movedTimes
import app.foscal.ui.editor.DatePickerModal
import app.foscal.ui.editor.TimePickerModal
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.rememberSkeletonFormatter
import app.foscal.ui.util.rememberTimeFormatter
import java.time.Instant
import java.time.ZoneId

/**
 * Picks a new day and start time for [event], keeping its length.
 *
 * The way to move an event without dragging it. A long-press drag cannot be performed through
 * TalkBack or Switch Access at all, and even by hand it only reaches the days on screen. The end is
 * shown rather than asked for, because a move that also changed the length would be an edit.
 */
@Composable
fun MoveEventDialog(
    event: Event,
    zone: ZoneId,
    onMove: (start: Instant, end: Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val originalDate = event.startLocalDate(zone)
    val originalTime = event.start.atZone(zone).toLocalTime()
    var date by remember(event) { mutableStateOf(originalDate) }
    var time by remember(event) { mutableStateOf(originalTime) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val (start, end) = movedTimes(event, date, time, zone)
    val dateFormat = rememberSkeletonFormatter("EEEMMMdy")
    val timeFormat = rememberTimeFormatter()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_title)) },
        text = {
            Column {
                PickerRow(
                    label = stringResource(R.string.move_date),
                    value = date.format(dateFormat),
                    onClick = { pickDate = true },
                )
                if (!event.allDay) {
                    PickerRow(
                        label = stringResource(R.string.editor_starts),
                        value = time.format(timeFormat),
                        onClick = { pickTime = true },
                    )
                    val endAt = end.atZone(zone)
                    Text(
                        if (endAt.toLocalDate() == date) {
                            stringResource(R.string.move_ends_at, endAt.format(timeFormat))
                        } else {
                            stringResource(
                                R.string.move_ends_on,
                                endAt.format(dateFormat),
                                endAt.format(timeFormat),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onMove(start, end) },
                // Putting it back where it was would write the event for nothing.
                enabled = date != originalDate || (!event.allDay && time != originalTime),
            ) { Text(stringResource(R.string.move_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (pickDate) {
        DatePickerModal(initial = date, onDismiss = { pickDate = false }, onSelect = { date = it })
    }
    if (pickTime) {
        TimePickerModal(
            initial = time,
            is24Hour = LocalUse24HourClock.current,
            onDismiss = { pickTime = false },
            onSelect = { time = it },
        )
    }
}

/** The label and its value as one control, so TalkBack reads "Date, Friday 3 October" together. */
@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
