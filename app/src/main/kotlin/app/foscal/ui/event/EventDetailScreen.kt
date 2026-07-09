package app.foscal.ui.event

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Event
import app.foscal.location.openInMaps
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.timeFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    eventId: Long,
    instanceStartMillis: Long = 0L,
    onBack: () -> Unit,
    onEdit: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onOpenLocationMap: (location: String) -> Unit = {},
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reload on every resume so returning from the editor reflects edits; show the spinner
    // only for the first fetch, refresh silently afterwards.
    LifecycleResumeEffect(eventId, instanceStartMillis) {
        viewModel.load(eventId, instanceStartMillis, showLoading = state.event == null)
        onPauseOrDispose {}
    }

    val event = state.event

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {},
                actions = {
                    if (event != null) {
                        IconButton(onClick = { onEdit(eventId, event.start.toEpochMilli()) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val phase = when {
            state.loading -> "loading"
            event == null -> "missing"
            else -> "content"
        }
        Crossfade(
            targetState = phase,
            animationSpec = tween(Motion.DurationMedium),
            label = "detailCrossfade",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { p ->
            when (p) {
                "loading" -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                "missing" -> Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Event not found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    // Crossfade keeps the old slot alive during a content→missing transition, so
                    // `phase` may be stale while `state.event` has already cleared. Re-check rather
                    // than `!!` to avoid an NPE mid-animation.
                    val current = state.event
                    if (current != null) {
                        DetailContent(
                            event = current,
                            calendarName = state.calendar?.displayName ?: "Calendar",
                            calendarColor = current.color,
                            mapsEnabled = state.mapsEnabled,
                            onOpenLocationMap = onOpenLocationMap,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(
    event: Event,
    calendarName: String,
    calendarColor: Int,
    mapsEnabled: Boolean,
    onOpenLocationMap: (location: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(calendarColor).copy(alpha = 0.14f))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabelPill(calendarName, calendarColor)
            Text(
                event.title,
                style = MaterialTheme.typography.headlineLarge.copy(fontFamily = BricolageFamily),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        InfoCard(Icons.Outlined.AccessTime, "When", formatWhen(event, LocalUse24HourClock.current))
        event.rrule?.takeIf { it.isNotBlank() }?.let {
            InfoCard(Icons.Outlined.Repeat, "Repeats", describeRecurrence(it))
        }
        event.location?.takeIf { it.isNotBlank() }?.let { location ->
            val context = LocalContext.current
            InfoCard(
                icon = Icons.Outlined.LocationOn,
                label = "Location",
                value = location,
                trailingIcon = Icons.Outlined.Map,
                // With the opt-in map on, show the place on an in-app OpenStreetMap; otherwise hand
                // the text to the device's maps app via a geo: intent so we stay offline.
                onClick = {
                    if (mapsEnabled) onOpenLocationMap(location) else openInMaps(context, location)
                },
            )
        }
        event.description?.takeIf { it.isNotBlank() }?.let {
            InfoCard(Icons.Outlined.Description, "Notes", it)
        }
    }
}

@Composable
private fun LabelPill(calendarName: String, calendarColor: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(calendarColor)),
        )
        Text(
            calendarName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
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
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(2.dp))
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
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

private fun formatWhen(event: Event, is24Hour: Boolean): String {
    val zone = ZoneId.systemDefault()
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    val timeFmt = timeFormatter(is24Hour)
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
