package app.calendarium.ui.month

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Event
import app.calendarium.ui.contrastColor
import app.calendarium.ui.util.Dates
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MonthRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheetDay by remember { mutableStateOf<LocalDate?>(null) }

    val baseMonth = remember { YearMonth.now() }
    val pageCount = 240 // ±10 years
    val initialPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val month = baseMonth.plusMonths((page - initialPage).toLong())
            viewModel.goToMonth(month)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.visibleMonth.format(Dates.monthYearFormatter),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(4.dp))
                        TextButton(onClick = {
                            viewModel.goToMonth(YearMonth.now())
                            scope.launch { pagerState.animateScrollToPage(initialPage) }
                        }) { Text("Today") }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, "Settings")
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
            WeekHeader()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                pageSpacing = 2.dp,
            ) { page ->
                val month = baseMonth.plusMonths((page - initialPage).toLong())
                val cells = remember(month) { Dates.monthCells(month) }
                MonthGrid(
                    cells = cells,
                    currentMonth = month,
                    eventsByDay = state.eventsByDay,
                    today = state.today,
                    selected = state.selectedDate,
                    onSelect = { date ->
                        viewModel.selectDate(date)
                        if (date != null) sheetDay = date
                    },
                )
            }
            if (!state.hasVisibleCalendars) {
                EmptyStateHint()
            }
        }
    }

    val dayForSheet = sheetDay
    if (dayForSheet != null) {
        DayEventsSheet(
            date = dayForSheet,
            events = state.eventsByDay[dayForSheet].orEmpty(),
            onEventClick = { id, instanceStart ->
                sheetDay = null
                onEventClick(id, instanceStart)
            },
            onNewEvent = {
                val zone = java.time.ZoneId.systemDefault()
                val start = dayForSheet.atStartOfDay(zone).plusHours(9)
                val end = start.plusHours(1)
                sheetDay = null
                onNewEvent(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            },
            onDismiss = { sheetDay = null },
        )
    }
}

@Composable
private fun WeekHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Dates.weekStartLabels().forEach { label ->
            Text(
                label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    cells: List<LocalDate>,
    currentMonth: YearMonth,
    eventsByDay: Map<LocalDate, List<Event>>,
    today: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = cells.chunked(7)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        rows.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                week.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                    ) {
                        DayCell(
                            date = date,
                            isInMonth = date.year == currentMonth.year && date.month == currentMonth.month,
                            events = eventsByDay[date].orEmpty(),
                            isToday = date == today,
                            isSelected = date == selected,
                            onClick = { onSelect(date) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isInMonth: Boolean,
    events: List<Event>,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    isSelected -> primary.copy(alpha = 0.12f)
                    isToday -> primary.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp, top = 2.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isToday) primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        !isInMonth -> muted
                        else -> onSurface
                    },
                )
            }
            EventChips(events.take(3), isInMonth = isInMonth)
            if (events.size > 3) {
                Text(
                    "+${events.size - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isInMonth) MaterialTheme.colorScheme.onSurfaceVariant else muted,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp, top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun EventChips(events: List<Event>, isInMonth: Boolean = true) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
    ) {
        events.forEach { event ->
            val base = Color(event.color)
            if (event.allDay) {
                // All-day: solid chip with contrast text — reads as a filled band, like other apps.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(base.copy(alpha = if (isInMonth) 0.9f else 0.35f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = contrastColor(event.color).copy(alpha = if (isInMonth) 1f else 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // Timed: a leading colour dot + title, so the day stays airy but scannable.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(base.copy(alpha = if (isInMonth) 1f else 0.4f)),
                    )
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = if (isInMonth) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No visible calendars. Tap the gear to enable one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
