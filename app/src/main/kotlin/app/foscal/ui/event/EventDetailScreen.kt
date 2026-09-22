package app.foscal.ui.event

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.foscal.core.ui.theme.LocalIsDarkTheme
import app.foscal.core.ui.theme.Motion
import app.foscal.location.openInMaps
import app.foscal.ui.editor.RecurrenceScope
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
    onDuplicate: (eventId: Long, instanceStartMillis: Long) -> Unit = { _, _ -> },
    onOpenLocationMap: (location: String) -> Unit = {},
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Nothing left to look at once it is deleted, and the list underneath re-reads on resume.
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
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
                                selfEmail = state.selfAttendee?.email,
                                reply = state.selfAttendee?.status.takeIf { state.canReply },
                                replyFailed = state.replyFailed,
                                onReply = viewModel::reply,
                                mapsEnabled = state.mapsEnabled,
                                onOpenLocationMap = onOpenLocationMap,
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
            if (event != null) {
                DetailActions(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(4.dp),
                    onEdit = { onEdit(eventId, event.start.toEpochMilli()) },
                    onDuplicate = { onDuplicate(eventId, event.start.toEpochMilli()) },
                    onDelete = viewModel::askDelete,
                )
            }
        }
    }

    if (state.deletePrompt) {
        DeleteDialog(
            recurring = event?.isRecurring == true,
            onDelete = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/**
 * Edit, and the things that are not edit.
 *
 * The pencil is on its own because it is the one action with a reason to be reached without
 * looking; duplicate and delete sit behind the overflow because a menu is a moment of thought,
 * and one of them removes the event. Both float over the header for the same reason the back
 * button does — there is no app bar here to hold them.
 */
@Composable
private fun DetailActions(
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Edit event")
        }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        open = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Confirming a delete, and for a series, deciding how much of it.
 *
 * A recurring event gets the three choices instead of a yes/no, because "delete" has no single
 * meaning for one: the dialog that asks how much to remove is also the one that asks whether to.
 */
@Composable
private fun DeleteDialog(
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
    /** The user's own address, so their row can be marked as theirs in the list. */
    selfEmail: String?,
    /** The user's current answer, or null when this is not an invitation they can answer. */
    reply: AttendeeStatus?,
    /** Whether the last answer was refused by the calendar. */
    replyFailed: Boolean,
    onReply: (AttendeeStatus) -> Unit,
    mapsEnabled: Boolean,
    onOpenLocationMap: (location: String) -> Unit,
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

        if (reply != null) {
            ReplyRow(current = reply, failed = replyFailed, onReply = onReply)
        }
        if (attendees.isNotEmpty()) {
            AttendeesCard(
                attendees = attendees,
                selfEmail = selfEmail,
                onEmail = { openMail(context, it) },
            )
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
private fun ReplyRow(
    current: AttendeeStatus,
    failed: Boolean,
    onReply: (AttendeeStatus) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "RSVP",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
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
        // Only when the write was refused. The chips do not move optimistically, so a reply that
        // worked needs no confirmation — but one that did not looks identical to one nobody
        // tapped, and a read-only calendar is not something the user can be expected to infer.
        AnimatedVisibility(visible = failed) {
            Text(
                "Your answer could not be saved. This calendar may be read-only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Who is on the event, folded away until it is asked for.
 *
 * A meeting from work arrives with thirty people on it, and thirty rows between the event and the
 * bottom of the screen is a wall rather than a list. Collapsed, the card answers the question the
 * names are usually opened for — how many are actually coming — in one line, and the names are one
 * tap behind it for when that is not enough.
 *
 * Tapping a row opens a mail composer. Answering for *yourself* is a write to your own attendee
 * row that the sync adapter delivers, which is what [ReplyRow] does; there is no equivalent for
 * anybody else, so handing their address to whatever mail app the user already has is the only
 * honest thing on offer here.
 */
@Composable
private fun AttendeesCard(
    attendees: List<Attendee>,
    selfEmail: String?,
    onEmail: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(Motion.DurationShort),
        label = "attendeesChevron",
    )
    val self = selfEmail?.let { Attendee.normalizeAddress(it) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (attendees.size == 1) "1 attendee" else "${attendees.size} attendees",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        attendees.answerSummary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Hide attendees" else "Show attendees",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    attendees.forEach { attendee ->
                        AttendeeRow(
                            attendee = attendee,
                            isSelf = self != null &&
                                Attendee.normalizeAddress(attendee.email) == self,
                            onEmail = onEmail,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One person, with their answer stated rather than implied.
 *
 * The answer used to be a grey tick at the end of the row and a word buried in a line of small
 * print with the address — three answers that all looked the same at a glance, which is the one
 * glance this list gets. Now it is a word in the colour of what it means, and the address moves
 * under the name where it belongs.
 */
@Composable
private fun AttendeeRow(attendee: Attendee, isSelf: Boolean, onEmail: (String) -> Unit) {
    val tint = attendee.status.tint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onEmail(attendee.email) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                if (isSelf) "${attendee.label} (you)" else attendee.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            attendee.detailLine().takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                attendee.statusIcon(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                attendee.statusLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** First letter of the display name, or of the address when there is none. */
private fun Attendee.initial(): String =
    label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** Who they are on this event and how to reach them; the answer itself sits at the end of the row. */
private fun Attendee.detailLine(): String = listOfNotNull(
    "Organizer".takeIf { isOrganizer },
    "Optional".takeIf { optional && !isOrganizer },
    email.takeIf { it != label },
).joinToString(" · ")

private fun Attendee.statusIcon(): ImageVector = when (status) {
    AttendeeStatus.ACCEPTED -> Icons.Outlined.CheckCircle
    AttendeeStatus.DECLINED -> Icons.Outlined.Cancel
    AttendeeStatus.TENTATIVE -> Icons.AutoMirrored.Outlined.HelpOutline
    AttendeeStatus.INVITED -> Icons.Outlined.Schedule
}

private fun Attendee.statusLabel(): String = when (status) {
    AttendeeStatus.ACCEPTED -> "Going"
    AttendeeStatus.DECLINED -> "Not going"
    AttendeeStatus.TENTATIVE -> "Maybe"
    AttendeeStatus.INVITED -> "No reply"
}

/** "4 going · 1 maybe · 2 no reply", with the answers nobody gave left out. */
private fun List<Attendee>.answerSummary(): String {
    val counts = listOf(
        AttendeeStatus.ACCEPTED to "going",
        AttendeeStatus.TENTATIVE to "maybe",
        AttendeeStatus.DECLINED to "not going",
        AttendeeStatus.INVITED to "no reply",
    )
    return counts
        .mapNotNull { (status, word) ->
            count { it.status == status }.takeIf { it > 0 }?.let { "$it $word" }
        }
        .joinToString(" · ")
}

/**
 * The colour an answer is drawn in.
 *
 * Fixed greens and ambers rather than roles from the scheme: the accent is the user's to pick, so
 * a "going" painted in it would mean something different on every install — and would match the
 * header, which is already the event's own colour and says nothing about anybody's answer. Error
 * is the exception, because a refusal is the one thing that role is actually for.
 */
@Composable
private fun AttendeeStatus.tint(): Color = when (this) {
    AttendeeStatus.ACCEPTED ->
        if (LocalIsDarkTheme.current) Color(0xFF74C79C) else Color(0xFF1B7A50)
    AttendeeStatus.DECLINED -> MaterialTheme.colorScheme.error
    AttendeeStatus.TENTATIVE ->
        if (LocalIsDarkTheme.current) Color(0xFFDCB55F) else Color(0xFF8A5E07)
    AttendeeStatus.INVITED -> MaterialTheme.colorScheme.onSurfaceVariant
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
