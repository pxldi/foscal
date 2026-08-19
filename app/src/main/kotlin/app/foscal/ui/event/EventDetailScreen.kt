package app.foscal.ui.event

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Event
import app.foscal.core.model.MeetingLinks
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
                                attendees = state.attendees,
                                reply = state.selfAttendee?.status.takeIf { state.canReply },
                                onReply = viewModel::reply,
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
    attendees: List<Attendee>,
    /** The user's current answer, or null when this is not an invitation they can answer. */
    reply: AttendeeStatus?,
    onReply: (AttendeeStatus) -> Unit,
    mapsEnabled: Boolean,
    onOpenLocationMap: (location: String) -> Unit,
    onEdit: () -> Unit,
) {
    val accent = Color(event.color)
    val context = LocalContext.current
    // The link is looked for in the location first: an invite whose location *is* the call means it
    // literally, while a description often quotes a dial-in or recording link further down too.
    val meetingUrl = remember(event.location, event.description) {
        MeetingLinks.find(event.location, event.description)
    }
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
            meetingUrl?.let { url ->
                DetailRow(
                    icon = Icons.Outlined.Videocam,
                    text = MeetingLinks.providerName(url)?.let { "Join $it" } ?: "Join video call",
                    onClick = { openLink(context, url) },
                )
            }
            event.rrule?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Outlined.Repeat, describeRecurrence(it))
            }
            // A location that is nothing but the call link is already the Join row above, and
            // handing a URL to a `geo:` intent searches a map for it — so it is suppressed
            // entirely in that case. One that merely *contains* a link ("Room B — https://…")
            // still names a real place and keeps its row.
            event.location
                ?.takeIf { it.isNotBlank() && it.trim() != meetingUrl }
                ?.let { location ->
                    DetailRow(
                        icon = Icons.Outlined.LocationOn,
                        text = location,
                        // With the opt-in map on, show the place on an in-app OpenStreetMap;
                        // otherwise hand the text to the device's maps app via a geo: intent so
                        // we stay offline.
                        onClick = {
                            if (mapsEnabled) {
                                onOpenLocationMap(location)
                            } else {
                                openInMaps(context, location)
                            }
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
                DetailRow(Icons.Outlined.Description, noteText(it))
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
        if (reply != null) {
            ReplyRow(current = reply, onReply = onReply)
        }
        if (attendees.isNotEmpty()) {
            GuestsCard(attendees = attendees, onEmail = { openMail(context, it) })
        }
    }
}

/**
 * Yes / Maybe / No for an invitation.
 *
 * Three buttons rather than a menu because the answer is the reason the screen was opened, and a
 * reply is worth exactly one tap. The current answer is filled in rather than merely marked, so a
 * glance says which one it is without reading all three.
 */
@Composable
private fun ReplyRow(current: AttendeeStatus, onReply: (AttendeeStatus) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Going?",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                AttendeeStatus.ACCEPTED to "Yes",
                AttendeeStatus.TENTATIVE to "Maybe",
                AttendeeStatus.DECLINED to "No",
            ).forEach { (status, label) ->
                FilterChip(
                    selected = current == status,
                    onClick = { onReply(status) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The guest list, organizer first, with each person's answer.
 *
 * Tapping a row opens a mail composer. Answering for *yourself* is a write to your own attendee
 * row that the sync adapter delivers, which is what [ReplyRow] does; there is no equivalent for
 * anybody else, so handing their address to whatever mail app the user already has is the only
 * honest thing on offer here.
 */
@Composable
private fun GuestsCard(attendees: List<Attendee>, onEmail: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (attendees.size == 1) "1 guest" else "${attendees.size} guests",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            attendees.forEach { attendee ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onEmail(attendee.email) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            attendee.initial(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            attendee.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            attendee.subtitle(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val statusIcon = when (attendee.status) {
                        AttendeeStatus.ACCEPTED -> Icons.Outlined.CheckCircle
                        AttendeeStatus.DECLINED -> Icons.Outlined.Cancel
                        AttendeeStatus.TENTATIVE -> Icons.AutoMirrored.Outlined.HelpOutline
                        AttendeeStatus.INVITED -> Icons.Outlined.Schedule
                    }
                    Icon(
                        statusIcon,
                        contentDescription = attendee.statusLabel(),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** First letter of the display name, or of the address when there is none. */
private fun Attendee.initial(): String =
    label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** The answer, plus the address when the name is what's already on the row above. */
private fun Attendee.subtitle(): String {
    val role = if (isOrganizer) "Organizer • " else ""
    val optionalMark = if (optional && !isOrganizer) " (optional)" else ""
    return if (label == email) {
        "$role${statusLabel()}$optionalMark"
    } else {
        "$role$email • ${statusLabel()}$optionalMark"
    }
}

private fun Attendee.statusLabel(): String = when (status) {
    AttendeeStatus.ACCEPTED -> "Going"
    AttendeeStatus.DECLINED -> "Not going"
    AttendeeStatus.TENTATIVE -> "Maybe"
    AttendeeStatus.INVITED -> "Awaiting reply"
}

/**
 * Hands [url] to whatever the user browses with.
 *
 * `resolveActivity` is deliberately not consulted first — package visibility on API 30+ hides
 * browsers this app has no `<queries>` entry for, so the check reports "nothing can open this" for
 * links that in fact open fine. Catching the failure covers the genuinely empty case.
 */
private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

private fun openMail(context: Context, email: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri()))
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

/**
 * An event's notes, as HTML when they are HTML.
 *
 * Only the detail screen renders them. The editor keeps showing the raw text, because it writes
 * the description back on save: rendering it there would mean saving the flattened version over
 * whatever the sync source put in, quietly stripping the formatting — and the meeting link — for
 * everyone else invited to the same event.
 */
@Composable
private fun noteText(raw: String): AnnotatedString =
    if (looksLikeHtml(raw)) {
        AnnotatedString.fromHtml(
            htmlString = raw,
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        )
    } else {
        AnnotatedString(raw)
    }

@Composable
private fun DetailRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) =
    DetailRow(icon = icon, text = AnnotatedString(text), onClick = onClick)

@Composable
private fun DetailRow(icon: ImageVector, text: AnnotatedString, onClick: (() -> Unit)? = null) {
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
