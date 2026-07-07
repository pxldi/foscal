package app.calendarium.ui.month

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Event
import app.calendarium.core.ui.theme.Motion
import app.calendarium.ui.util.Dates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MonthRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedDate = state.selectedDate ?: state.today
    val selectedEvents = state.eventsByDay[selectedDate].orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    MonthTitle(
                        month = state.visibleMonth,
                        onNextMonth = { viewModel.nextMonth() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.previousMonth() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                },
                actions = {
                    TodayPill(onClick = { viewModel.goToMonth(YearMonth.now()) })
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        onClick = onOpenSearch,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Search, "Search")
                        }
                    }
                    Spacer(Modifier.size(8.dp))
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
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = state.visibleMonth,
                    transitionSpec = {
                        val direction = if (targetState > initialState) {
                            AnimatedContentTransitionScope.SlideDirection.Start
                        } else {
                            AnimatedContentTransitionScope.SlideDirection.End
                        }
                        (slideIntoContainer(direction, tween(Motion.DurationMedium)) +
                            fadeIn(tween(Motion.DurationMedium))) togetherWith
                            (slideOutOfContainer(direction, tween(Motion.DurationMedium)) +
                                fadeOut(tween(Motion.DurationMedium)))
                    },
                    label = "monthGrid",
                    modifier = Modifier.fillMaxSize(),
                ) { visibleMonth ->
                    val rows = remember(visibleMonth) { visibleMonthCells(visibleMonth).chunked(7) }
                    val rowHeight = maxHeight / rows.size.coerceAtLeast(1)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        rows.forEach { week ->
                            MonthWeekRow(
                                weekStart = week.first(),
                                focusedMonth = visibleMonth,
                                today = state.today,
                                selectedDate = state.selectedDate,
                                eventsByDay = state.eventsByDay,
                                rowHeight = rowHeight,
                                onDayClick = { date ->
                                    viewModel.selectDate(date)
                                },
                            )
                        }
                    }
                }
            }
            DayPreviewPanel(
                date = selectedDate,
                events = selectedEvents,
                onEventClick = onEventClick,
                onNewEvent = {
                    val zone = ZoneId.systemDefault()
                    val start = selectedDate.atStartOfDay(zone).plusHours(9)
                    val end = start.plusHours(1)
                    onNewEvent(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
                },
                modifier = Modifier.weight(1f),
            )
            if (!state.hasVisibleCalendars) {
                EmptyStateHint()
            }
        }
    }
}

@Composable
private fun MonthTitle(month: YearMonth, onNextMonth: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                append(
                    month.month.getDisplayName(
                        TextStyle.FULL,
                        Locale.getDefault(),
                    ),
                )
                append(" ")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal,
                    ),
                ) {
                    append(month.year.toString())
                }
            },
            fontFamily = FontFamily.SansSerif,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNextMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@Composable
private fun TodayPill(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun DayPreviewPanel(
    date: LocalDate,
    events: List<Event>,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onNewEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 56.dp, bottom = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = date.format(
                                DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = if (events.isEmpty()) "No events" else {
                                "${events.size} event${if (events.size == 1) "" else "s"}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (events.isEmpty()) {
                    Text(
                        "Tap + to add something to this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            events.sortedBy { it.start },
                            key = { it.id to it.start.toEpochMilli() },
                        ) { event ->
                            MonthPreviewEventRow(
                                event = event,
                                onClick = { onEventClick(event.id, event.start.toEpochMilli()) },
                            )
                        }
                    }
                }
            }
            Surface(
                onClick = onNewEvent,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 16.dp)
                    .size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "New event")
                }
            }
        }
    }
}

@Composable
private fun MonthPreviewEventRow(event: Event, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(event.color)),
        )
        Text(
            text = previewTimeLabel(event),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.size(width = 54.dp, height = 18.dp),
        )
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun previewTimeLabel(event: Event): String =
    if (event.allDay) {
        "All day"
    } else {
        event.start.atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

@Composable
private fun WeekHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Dates.weekStartLabels().forEachIndexed { index, label ->
            Text(
                label,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 7.dp),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (index >= 5) {
                    Color(0xFFA47700)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun MonthWeekRow(
    weekStart: LocalDate,
    focusedMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate?,
    eventsByDay: Map<LocalDate, List<Event>>,
    rowHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
) {
    val weekDates = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            weekDates.forEach { date ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DayCell(
                        date = date,
                        isInFocusedMonth = YearMonth.from(date) == focusedMonth,
                        events = eventsByDay[date].orEmpty(),
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
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
    val shape = RoundedCornerShape(12.dp)
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
    val selectedCircle by animateColorAsState(
        targetValue = if (isSelected) primary else Color.Transparent,
        animationSpec = tween(Motion.DurationMedium),
        label = "selectedDayCircle",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(selectedCircle),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.error else dayNumberColor,
                    maxLines = 1,
                )
            }
            EventDots(events.take(4), inMonthFraction = fraction)
        }
    }
}

@Composable
private fun EventDots(events: List<Event>, inMonthFraction: Float = 1f) {
    val f = inMonthFraction
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(top = 5.dp)
            .height(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        events.forEach { event ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color(event.color).copy(alpha = lerpFloat(0.4f, 1f, f))),
            )
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

private fun visibleMonthCells(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): List<LocalDate> {
    val first = month.atDay(1)
    val offset = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val origin = first.minusDays(offset.toLong())
    val last = month.atEndOfMonth()
    val trailing = (firstDayOfWeek.value - last.dayOfWeek.value - 1 + 7) % 7
    val end = last.plusDays(trailing.toLong())
    val days = ChronoUnit.DAYS.between(origin, end).toInt() + 1
    return (0 until days).map { origin.plusDays(it.toLong()) }
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
