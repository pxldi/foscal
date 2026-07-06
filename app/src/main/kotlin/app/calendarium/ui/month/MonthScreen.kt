package app.calendarium.ui.month

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Event
import app.calendarium.core.ui.theme.Motion
import app.calendarium.ui.contrastColor
import app.calendarium.ui.util.Dates
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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

    // Continuous vertical scroll of weeks (not paged months): every calendar week is rendered
    // exactly once, so adjacent months never draw the same boundary days twice. The "focused"
    // month — which drives the title and the crisp/greyed fade — is derived from scroll position.
    val firstDayOfWeek = DayOfWeek.MONDAY
    val baseWeekStart = remember {
        LocalDate.now().with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    }
    val weekCount = 52 * 20 // ±10 years of weeks
    val initialIndex = weekCount / 2
    val scope = rememberCoroutineScope()

    fun weekStartAt(index: Int): LocalDate =
        baseWeekStart.plusWeeks((index - initialIndex).toLong())

    // Row index whose week contains the 1st of [month] — used to align a month to the top.
    fun indexForMonth(month: YearMonth): Int {
        val firstWeek = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        return initialIndex + ChronoUnit.WEEKS.between(baseWeekStart, firstWeek).toInt()
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = indexForMonth(YearMonth.now()),
    )

    // A week belongs to the month of its Thursday (ISO): the month holding the majority of its days.
    val focusedMonth by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val item = info.visibleItemsInfo.firstOrNull { it.offset + it.size > center }
                ?: info.visibleItemsInfo.firstOrNull()
            val index = item?.index ?: initialIndex
            YearMonth.from(weekStartAt(index).plusDays(3))
        }
    }

    LaunchedEffect(focusedMonth) { viewModel.goToMonth(focusedMonth) }

    // When scrolling settles, snap the focused month's first week to the top so a month always
    // rests as a whole page (no landing mid-month) while free-scrolling still fades between them.
    // The snap runs in `scope`, not the collect coroutine: a user gesture interrupting it cancels
    // only that job. If it ran inline, the first interruption would throw CancellationException out
    // of collect, killing the collector for good (keyed on stable listState it never restarts) —
    // which is why the arrows snapped fine but manual scrolling didn't.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                scope.launch {
                    val target = indexForMonth(focusedMonth)
                    if (listState.firstVisibleItemIndex != target ||
                        listState.firstVisibleItemScrollOffset != 0
                    ) {
                        listState.animateScrollToItem(target)
                    }
                }
            }
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
                            scope.launch { listState.animateScrollToItem(indexForMonth(YearMonth.now())) }
                        }) { Text("Today") }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { listState.animateScrollToItem(indexForMonth(focusedMonth.minusMonths(1))) }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { listState.animateScrollToItem(indexForMonth(focusedMonth.plusMonths(1))) }
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Six weeks fill the viewport, so the focused month reads as a familiar page.
                val rowHeight = maxHeight / 6
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(weekCount, key = { it }) { index ->
                        val weekStart = weekStartAt(index)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (d in 0 until 7) {
                                val date = weekStart.plusDays(d.toLong())
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    DayCell(
                                        date = date,
                                        isInFocusedMonth = YearMonth.from(date) == focusedMonth,
                                        events = state.eventsByDay[date].orEmpty(),
                                        isToday = date == state.today,
                                        isSelected = date == state.selectedDate,
                                        onClick = {
                                            viewModel.selectDate(date)
                                            sheetDay = date
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
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
private fun DayCell(
    date: LocalDate,
    isInFocusedMonth: Boolean,
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
    // 1f = the focused month (crisp/black), 0f = an adjacent month (greyed). Animating it means
    // days entering the focused month fade to black and departing days fade to grey.
    val fraction by animateFloatAsState(
        targetValue = if (isInFocusedMonth) 1f else 0f,
        animationSpec = tween(Motion.DurationMedium),
        label = "inMonthFraction",
    )
    val dayNumberColor = lerp(muted, onSurface, fraction)
    val bg by animateColorAsState(
        targetValue = when {
            isSelected -> primary.copy(alpha = 0.12f)
            isToday -> primary.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(Motion.DurationMedium),
        label = "dayCellBg",
    )
    val todayCircle by animateColorAsState(
        targetValue = if (isToday) primary else Color.Transparent,
        animationSpec = tween(Motion.DurationMedium),
        label = "todayCircle",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
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
                    .background(todayCircle),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else dayNumberColor,
                )
            }
            EventChips(events.take(3), inMonthFraction = fraction)
            if (events.size > 3) {
                Text(
                    "+${events.size - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = lerp(muted, MaterialTheme.colorScheme.onSurfaceVariant, fraction),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 2.dp, top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun EventChips(events: List<Event>, inMonthFraction: Float = 1f) {
    val f = inMonthFraction
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
                        .background(base.copy(alpha = lerpFloat(0.35f, 0.9f, f)))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = contrastColor(event.color).copy(alpha = lerpFloat(0.6f, 1f, f)),
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
                            .background(base.copy(alpha = lerpFloat(0.4f, 1f, f))),
                    )
                    Text(
                        event.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = lerp(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.onSurface,
                            f,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

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
