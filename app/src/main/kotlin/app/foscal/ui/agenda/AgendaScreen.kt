package app.foscal.ui.agenda

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Event
import app.foscal.ui.util.Dates
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.timeFormatter
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val positionedAtToday = remember { mutableStateOf(false) }
    var olderRequestedAt by remember { mutableStateOf<LocalDate?>(null) }
    var newerRequestedAt by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(state.days) {
        if (!positionedAtToday.value && state.days.isNotEmpty()) {
            positionedAtToday.value = true
            val index = state.days.indexOfFirst { !it.date.isBefore(state.today) }
                .takeIf { it >= 0 } ?: state.days.lastIndex
            listState.scrollToItem(index.coerceAtLeast(0))
        }
    }

    LaunchedEffect(listState, state.days.size) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: -1
            val last = visible.lastOrNull()?.index ?: -1
            Triple(first, last, listState.isScrollInProgress)
        }.distinctUntilChanged().collect { (first, last, isScrolling) ->
            if (state.days.isEmpty()) return@collect
            if (!isScrolling) return@collect
            val firstDate = state.days.first().date
            val lastDate = state.days.last().date
            if (first in 0..2 && olderRequestedAt != firstDate) {
                olderRequestedAt = firstDate
                viewModel.loadOlder()
            }
            if (last >= state.days.lastIndex - 2 && newerRequestedAt != lastDate) {
                newerRequestedAt = lastDate
                viewModel.loadNewer()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Agenda", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, "Search")
                    }
                },
            )
        },
    ) { padding ->
        if (state.days.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.hasVisibleCalendars)
                        "No events in the loaded agenda range."
                    else
                        "No visible calendars. Open Settings to enable one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.days, key = { it.date }) { day ->
                AgendaDayRow(
                    day = day,
                    today = state.today,
                    onEventClick = onEventClick,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun AgendaDayRow(
    day: AgendaDay,
    today: LocalDate,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.size(width = 56.dp, height = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (day.date == today) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = if (day.date == today) {
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            } else {
                Modifier
            },
        )
            Text(
                text = day.date.format(Dates.fullWeekdayFormatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            day.events.forEach { event ->
                AgendaEventCard(
                    event = event,
                    date = day.date,
                    onClick = { onEventClick(event.id, event.start.toEpochMilli()) },
                )
            }
        }
    }
}

@Composable
private fun AgendaEventCard(event: Event, date: LocalDate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(event.color)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildEventSubtitle(event, date, LocalUse24HourClock.current)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun buildEventSubtitle(event: Event, date: LocalDate, is24Hour: Boolean): String {
    val parts = mutableListOf<String>()
    if (event.allDay) {
        parts += "All day"
    } else {
        val zone = ZoneId.systemDefault()
        val fmt = timeFormatter(is24Hour)
        val startT = Dates.instantToLocal(event.start).toLocalTime().format(fmt)
        val endT = Dates.instantToLocal(event.end).toLocalTime().format(fmt)
        // Use the model's inclusive last day so an event ending exactly at midnight is treated as
        // single-day (matching the days it actually appears on), not a phantom overnight span.
        val firstDay = event.startLocalDate(zone)
        val lastDay = event.lastLocalDate(zone)
        parts += when {
            firstDay == lastDay -> "$startT – $endT"
            // Genuine multi-day timed event: show only the portion that belongs to this day so
            // the times aren't misread as a single-day span (e.g. a bare "22:00 – 03:00").
            date == firstDay -> "$startT → overnight"
            date == lastDay -> "Until $endT"
            else -> "All day"
        }
    }
    if (!event.location.isNullOrBlank()) parts += event.location!!
    return parts.joinToString(" • ")
}
