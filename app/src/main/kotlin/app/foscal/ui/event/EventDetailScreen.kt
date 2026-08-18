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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Event
import app.foscal.core.model.ReminderDuration
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.Motion
import app.foscal.location.openInMaps
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.timeFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // No top bar. An app bar would paint its own surface across the top of the screen, and the
    // header's gradient would start underneath it — a grey band above a coloured one, with a seam
    // between them. Instead the header runs to the top edge and the back button floats on it.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val phase = when {
            state.loading -> "loading"
            event == null -> "missing"
            else -> "content"
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            Crossfade(
                targetState = phase,
                animationSpec = tween(Motion.DurationShort),
                label = "detailCrossfade",
                modifier = Modifier.fillMaxSize(),
            ) { p ->
                when (p) {
                    "loading" -> Centered { CircularProgressIndicator() }

                    "missing" -> Centered {
                        Text("Event not found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    else -> {
                        // Crossfade keeps the old slot alive during a content -> missing change, so
                        // `phase` may be stale while `state.event` has already cleared. Re-check
                        // rather than `!!` to avoid an NPE mid-animation.
                        val current = state.event
                        if (current == null) {
                            Centered { CircularProgressIndicator() }
                        } else {
                            DetailContent(
                                event = current,
                                calendarName = state.calendar?.displayName ?: "Calendar",
                                reminderMinutes = state.reminderMinutes,
                                mapsEnabled = state.mapsEnabled,
                                onOpenLocationMap = onOpenLocationMap,
                                onEdit = { onEdit(eventId, current.start.toEpochMilli()) },
                            )
                        }
                    }
                }
            }
            // Floated over the header rather than sitting in an app bar, so the gradient can own
            // the whole top of the screen.
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

/**
 * Title, when, and then whatever else this event happens to have.
 *
 * The old version wrapped every fact in its own outlined card, which made the notes look exactly
 * as important as the time. Here the header carries the two things you opened the screen for — what
 * it is and when it is — and everything below is a plain list that only draws a container when a
 * row actually does something when tapped.
 */
@Composable
private fun DetailContent(
    event: Event,
    calendarName: String,
    reminderMinutes: List<Int>,
    mapsEnabled: Boolean,
    onOpenLocationMap: (location: String) -> Unit,
    onEdit: () -> Unit,
) {
    val accent = Color(event.color)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Header(event = event, calendarName = calendarName, accent = accent)

        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            event.rrule?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Outlined.Repeat, describeRecurrence(it))
            }
            event.location?.takeIf { it.isNotBlank() }?.let { location ->
                val context = LocalContext.current
                DetailRow(
                    icon = Icons.Outlined.LocationOn,
                    text = location,
                    // With the opt-in map on, show the place on an in-app OpenStreetMap; otherwise
                    // hand the text to the device's maps app via a geo: intent so we stay offline.
                    onClick = {
                        if (mapsEnabled) onOpenLocationMap(location) else openInMaps(context, location)
                    },
                )
            }
            if (reminderMinutes.isNotEmpty()) {
                DetailRow(
                    icon = Icons.Outlined.Notifications,
                    text = reminderMinutes.joinToString(" · ") { ReminderDuration.label(it) },
                )
            }
            event.description?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Outlined.Description, it)
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onEdit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text("Edit event", fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The event's own colour, washed out from the top so the title sits in it rather than on a slab.
 *
 * A flat tinted block read as a card that had lost its edges; a gradient that fades into the page
 * lets the header end without a line across the screen. It starts at the very top of the window —
 * behind the status bar — because a gradient that begins below a bar of some other colour has a
 * seam at the top, which is the one place it is most visible.
 */
@Composable
private fun Header(event: Event, calendarName: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.28f),
                    0.55f to accent.copy(alpha = 0.10f),
                    1f to MaterialTheme.colorScheme.surface,
                ),
            )
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
            Text(
                calendarName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            event.title,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = BricolageFamily),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Promoted out of the list: with the title, this is the whole reason the screen was opened.
        Text(
            formatWhen(event, LocalUse24HourClock.current, currentLocale()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
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

/**
 * "Wed, 19 Aug · 09:00 – 10:00", or the all-day equivalent.
 *
 * Dates come from the model's own helpers rather than from `atZone(zone)`: an all-day event is
 * stored at UTC midnight with an *exclusive* end, so reading it in the device zone shows the wrong
 * day for anyone west of UTC and always shows one day too many at the end.
 */
private fun formatWhen(event: Event, is24Hour: Boolean, locale: Locale): String {
    val zone = ZoneId.systemDefault()
    val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    val timeFmt = timeFormatter(is24Hour, locale)
    val firstDay = event.startLocalDate(zone)
    val lastDay = event.lastLocalDate(zone).coerceAtLeast(firstDay)

    if (event.allDay) {
        return if (firstDay == lastDay) {
            "All day · ${firstDay.format(dateFmt)}"
        } else {
            "All day · ${firstDay.format(dateFmt)} – ${lastDay.format(dateFmt)}"
        }
    }
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    return if (firstDay == lastDay) {
        "${firstDay.format(dateFmt)}\n${start.format(timeFmt)} – ${end.format(timeFmt)}"
    } else {
        "${firstDay.format(dateFmt)} ${start.format(timeFmt)}\n– ${lastDay.format(dateFmt)} ${end.format(timeFmt)}"
    }
}
