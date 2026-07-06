package app.calendarium.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Event
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    eventId: Long,
    instanceStartMillis: Long = 0L,
    onDismiss: () -> Unit,
    onEdit: (eventId: Long, instanceStartMillis: Long) -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(eventId, instanceStartMillis) { viewModel.load(eventId, instanceStartMillis) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.event == null -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Event not found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> DetailContent(
                event = state.event!!,
                calendarName = state.calendar?.displayName ?: "Calendar",
                calendarColor = state.event!!.color,
                onEdit = { onEdit(eventId, state.event!!.start.toEpochMilli()) },
            )
        }
    }
}

@Composable
private fun DetailContent(
    event: Event,
    calendarName: String,
    calendarColor: Int,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Hero header: color stripe + title + calendar name + edit
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(calendarColor)),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(calendarColor)),
                    )
                    Text(
                        calendarName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalIconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit")
            }
        }

        InfoCard(Icons.Outlined.AccessTime, "When", formatWhen(event))
        event.rrule?.takeIf { it.isNotBlank() }?.let {
            InfoCard(Icons.Outlined.Repeat, "Repeats", describeRecurrence(it))
        }
        event.location?.takeIf { it.isNotBlank() }?.let {
            InfoCard(Icons.Outlined.LocationOn, "Location", it)
        }
        event.description?.takeIf { it.isNotBlank() }?.let {
            InfoCard(Icons.Outlined.Description, "Notes", it)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun describeRecurrence(rrule: String): String {
    val freq = rrule.split(';')
        .firstOrNull { it.startsWith("FREQ=") }
        ?.substringAfter('=')
        ?.uppercase()
    return when (freq) {
        "DAILY" -> "Every day"
        "WEEKLY" -> "Every week"
        "MONTHLY" -> "Every month"
        "YEARLY" -> "Every year"
        else -> "Repeats"
    }
}

private fun formatWhen(event: Event): String {
    val zone = ZoneId.systemDefault()
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    return if (event.allDay) {
        if (start.toLocalDate() == end.toLocalDate().minusDays(1)) {
            "All day • ${start.toLocalDate().format(dateFmt)}"
        } else {
            "All day • ${start.toLocalDate().format(dateFmt)} – ${end.toLocalDate().minusDays(1).format(dateFmt)}"
        }
    } else {
        if (start.toLocalDate() == end.toLocalDate()) {
            "${start.format(dateFmt)}\n${start.format(timeFmt)} – ${end.format(timeFmt)}"
        } else {
            "${start.format(dateFmt)} ${start.format(timeFmt)}\n– ${end.format(dateFmt)} ${end.format(timeFmt)}"
        }
    }
}
