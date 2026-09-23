package app.foscal.ui.week

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.ui.theme.Motion
import app.foscal.core.ui.theme.onTodayDiscColor
import app.foscal.core.ui.theme.todayDiscColor
import app.foscal.core.ui.theme.weekendLabelColor
import app.foscal.ui.common.RecurrenceScopeDialog
import app.foscal.ui.common.TimelineDay
import app.foscal.ui.common.TimelineEndInset
import app.foscal.ui.common.TimelineGutterWidth
import app.foscal.ui.common.TimelineLayout
import app.foscal.ui.common.pageOnSwipe
import app.foscal.ui.home.BehaviourViewModel
import app.foscal.ui.util.currentLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A drop that is waiting on the user to say how much of a series it applies to. */
private data class PendingMove(
    val event: app.foscal.core.model.Event,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * The timeline, at whatever zoom [span] asks for: 1 day, 3 days or a week.
 *
 * One screen for all three because they *are* one screen — `TimelineLayout` already divides its
 * width by `days.size`, so the only difference between Day and Week is how many entries are in
 * the list handed to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineRoute(
    span: Int,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    viewModel: WeekViewModel = hiltViewModel(),
    behaviourViewModel: BehaviourViewModel = hiltViewModel(),
) {
    LaunchedEffect(span) { viewModel.setSpan(span) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val behaviour by behaviourViewModel.state.collectAsStateWithLifecycle()

    // A drop on a series is a question, not an instruction. It used to be answered for the user —
    // always "just this one" — which turned the occurrence into a lone exception carrying no rule,
    // so the editor then reported it as repeating "Once" and the drag looked like it had thrown
    // the repeat away.
    var pendingMove by remember { mutableStateOf<PendingMove?>(null) }
    var revertMoveSignal by remember { mutableIntStateOf(0) }
    val moveRefusals by viewModel.moveRefusals.collectAsStateWithLifecycle()

    pendingMove?.let { move ->
        RecurrenceScopeDialog(
            verb = "Move",
            onScope = { scope ->
                pendingMove = null
                viewModel.moveEvent(move.event, move.startMillis, move.endMillis, scope)
            },
            onDismiss = {
                pendingMove = null
                revertMoveSignal++
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Text(
                        formatSpanRange(state.anchor, state.spanDays, currentLocale(), state.today.year),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.previous() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.next() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Safe over the grid below: it arbitrates the axis before the grid sees the
                // drag, and hands anything that is not a sideways swipe straight back — so
                // scrolling the timeline and dragging an event block around still work, and a
                // sideways swipe no longer loses to the scroller it happens to start on top of.
                .pageOnSwipe(onPrevious = viewModel::previous, onNext = viewModel::next),
        ) {
            AnimatedContent(
                targetState = state.anchor to state.spanDays,
                transitionSpec = {
                    if (initialState.second != targetState.second) {
                        // A zoom change, not a move through time — there is no left or right for it
                        // to slide towards, so it dissolves instead.
                        fadeIn(tween(Motion.DurationShort)) togetherWith
                            fadeOut(tween(Motion.DurationShort))
                    } else {
                        val direction = if (targetState.first > initialState.first) {
                            AnimatedContentTransitionScope.SlideDirection.Start
                        } else {
                            AnimatedContentTransitionScope.SlideDirection.End
                        }
                        slideIntoContainer(direction, tween(Motion.DurationMedium)) togetherWith
                            slideOutOfContainer(direction, tween(Motion.DurationMedium))
                    }
                },
                label = "timelineNav",
                modifier = Modifier.fillMaxSize(),
            ) { (anchor, days) ->
                // Built here rather than taken from state.days so the page sliding out keeps the
                // events it was showing instead of repainting with the incoming page's.
                val visible = remember(anchor, days, state.eventsByDay) {
                    (0 until days).map { offset ->
                        val date = anchor.plusDays(offset.toLong())
                        TimelineDay(date, state.eventsByDay[date].orEmpty())
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    TimelineDayHeader(
                        anchor = anchor,
                        spanDays = days,
                        today = state.today,
                        // Tapping a day zooms into it. Only when there is more than one on screen:
                        // in Day view the header names the page you are already on.
                        onDayClick = if (days > 1) onOpenDay else null,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    WeekScheduleList(
                        days = visible,
                        // A single column has room for the time as well as the title; seven
                        // narrow ones do not. Same component, different breathing room.
                        compact = visible.size > 1,
                        onEventClick = onEventClick,
                        onNewEvent = onNewEvent,
                        onEventMove = { event, startMillis, endMillis ->
                            if (event.isRecurring) {
                                pendingMove = PendingMove(event, startMillis, endMillis)
                            } else {
                                viewModel.moveEvent(event, startMillis, endMillis)
                            }
                        },
                        newEventMinutes = behaviour.defaultEventMinutes,
                        revertMoveSignal = revertMoveSignal + moveRefusals,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekScheduleList(
    days: List<app.foscal.ui.common.TimelineDay>,
    compact: Boolean,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    onEventMove: (app.foscal.core.model.Event, Long, Long) -> Unit,
    newEventMinutes: Int,
    revertMoveSignal: Int,
    modifier: Modifier = Modifier,
) {
    TimelineLayout(
        days = days,
        onEventClick = onEventClick,
        onTimeRangeSelected = onNewEvent,
        onEventMove = onEventMove,
        newEventMinutes = newEventMinutes,
        revertMoveSignal = revertMoveSignal,
        modifier = modifier,
        compact = compact,
    )
}

@Composable
private fun TimelineDayHeader(
    anchor: LocalDate,
    spanDays: Int,
    today: LocalDate,
    onDayClick: ((LocalDate) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = TimelineGutterWidth,
                end = TimelineEndInset,
                top = 4.dp,
                bottom = 4.dp,
            ),
    ) {
        val locale = currentLocale()
        (0 until spanDays).forEach { offset ->
            val date = anchor.plusDays(offset.toLong())
            val isToday = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (onDayClick == null) {
                            Modifier
                        } else {
                            Modifier.clickable { onDayClick(date) }
                        },
                    )
                    .padding(vertical = 2.dp),
            ) {
                val weekend = date.dayOfWeek == java.time.DayOfWeek.SATURDAY ||
                    date.dayOfWeek == java.time.DayOfWeek.SUNDAY
                Text(
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    // The same gold the month grid gives its weekend columns.
                    color = if (weekend) weekendLabelColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isToday) todayDiscColor() else Color.Transparent)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) onTodayDiscColor()
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * "Mon, Aug 17" for a single day; "Aug 17 – 23" or "Jul 30 – Aug 5" for a range. Outside
 * [currentYear] the year is added, as the month header does, since paging a week at a time gives
 * no other clue which year is on screen: "Dec 28, 2026 – Jan 3, 2027".
 */
internal fun formatSpanRange(start: LocalDate, spanDays: Int, locale: Locale, currentYear: Int): String {
    val end = start.plusDays((spanDays - 1L).coerceAtLeast(0L))
    val withYear = start.year != currentYear || end.year != currentYear
    if (spanDays <= 1) {
        val pattern = if (withYear) "EEE, MMM d, yyyy" else "EEE, MMM d"
        return start.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val f = DateTimeFormatter.ofPattern("MMM d", locale)
    return when {
        start.year != end.year -> {
            val y = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
            "${start.format(y)} – ${end.format(y)}"
        }
        start.month == end.month -> {
            val month = start.month.getDisplayName(java.time.format.TextStyle.SHORT, locale)
            "$month ${start.dayOfMonth} – ${end.dayOfMonth}" + if (withYear) ", ${start.year}" else ""
        }
        else -> "${start.format(f)} – ${end.format(f)}" + if (withYear) ", ${start.year}" else ""
    }
}
