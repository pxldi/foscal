package app.calendarium.ui.week

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Event
import app.calendarium.core.ui.theme.Motion
import app.calendarium.ui.util.Dates
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val weekEnd = state.weekStart.plusDays(6)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatWeekRange(state.weekStart, weekEnd),
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { viewModel.goToThisWeek() }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                                Text("Today")
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.previousWeek() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous week")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.nextWeek() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next week")
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, "Search")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Crossfade(
                targetState = state.weekStart,
                animationSpec = tween(Motion.DurationMedium),
                label = "weekNav",
                modifier = Modifier.fillMaxSize(),
            ) { weekStart ->
                Column(modifier = Modifier.fillMaxSize()) {
                    WeekDayHeader(weekStart = weekStart, today = state.today)
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    WeekScheduleList(
                        days = state.days,
                        today = state.today,
                        onEventClick = onEventClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekScheduleList(
    days: List<app.calendarium.ui.common.TimelineDay>,
    today: LocalDate,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val daysWithEvents = days.filter { it.events.isNotEmpty() }
    if (daysWithEvents.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "No events this week.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 10.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(daysWithEvents, key = { it.date }) { day ->
            WeekScheduleDay(
                date = day.date,
                today = today,
                events = day.events,
                onEventClick = onEventClick,
            )
        }
    }
}

@Composable
private fun WeekScheduleDay(
    date: LocalDate,
    today: LocalDate,
    events: List<Event>,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (date == today) "TODAY" else date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.SHORT,
                Locale.getDefault(),
            ).uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (date == today) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            events.forEach { event ->
                WeekEventCard(
                    event = event,
                    date = date,
                    onClick = { onEventClick(event.id, event.start.toEpochMilli()) },
                )
            }
        }
    }
}

@Composable
private fun WeekEventCard(event: Event, date: LocalDate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            eventTimeLabel(event),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(58.dp),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(event.color)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = weekEventSubtitle(event, date)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun eventTimeLabel(event: Event): String =
    if (event.allDay) {
        "ALL-DAY"
    } else {
        Dates.instantToLocal(event.start).toLocalTime().format(Dates.timeFormatter)
    }

private fun weekEventSubtitle(event: Event, date: LocalDate): String {
    val parts = mutableListOf<String>()
    if (!event.allDay) {
        val zone = ZoneId.systemDefault()
        val startT = Dates.instantToLocal(event.start, zone).toLocalTime().format(Dates.timeFormatter)
        val endT = Dates.instantToLocal(event.end, zone).toLocalTime().format(Dates.timeFormatter)
        val firstDay = event.startLocalDate(zone)
        val lastDay = event.lastLocalDate(zone)
        parts += when {
            firstDay == lastDay -> "$startT – $endT"
            date == firstDay -> "$startT → overnight"
            date == lastDay -> "Until $endT"
            else -> "All day"
        }
    }
    if (!event.location.isNullOrBlank()) parts += event.location!!
    return parts.joinToString(" · ")
}

@Composable
private fun WeekDayHeader(weekStart: LocalDate, today: LocalDate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        (0 until 7).forEach { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val isToday = date == today
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(
                        java.time.format.TextStyle.NARROW,
                        Locale.getDefault(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
    val sameMonth = start.month == end.month
    val f = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return if (sameMonth) {
        "${start.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())} ${start.dayOfMonth} – ${end.dayOfMonth}"
    } else {
        "${start.format(f)} – ${end.format(f)}"
    }
}
