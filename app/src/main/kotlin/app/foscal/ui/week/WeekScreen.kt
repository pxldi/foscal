package app.foscal.ui.week

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.common.TimelineLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
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
                        onEventClick = onEventClick,
                        onNewEvent = onNewEvent,
                        onEventMove = viewModel::moveEvent,
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
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    onEventMove: (app.foscal.core.model.Event, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    TimelineLayout(
        days = days,
        onEventClick = onEventClick,
        onTimeRangeSelected = onNewEvent,
        onEventMove = onEventMove,
        modifier = modifier,
        compact = true,
    )
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
