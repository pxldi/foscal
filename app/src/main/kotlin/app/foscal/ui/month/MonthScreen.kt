package app.foscal.ui.month

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Event
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.util.Dates
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.rememberDateFormatter
import app.foscal.ui.util.timeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
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
    var showMonthPicker by remember { mutableStateOf(false) }
    var monthTransitionAxis by remember { mutableStateOf(MonthSwipeAxis.Horizontal) }

    if (showMonthPicker) {
        MonthJumpDialog(
            initialMonth = state.visibleMonth,
            onDismiss = { showMonthPicker = false },
            onSelect = { month ->
                showMonthPicker = false
                monthTransitionAxis = MonthSwipeAxis.Horizontal
                viewModel.goToMonth(month)
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    MonthTitle(
                        month = state.visibleMonth,
                        onClick = { showMonthPicker = true },
                    )
                },
                actions = {
                    TodayPill(
                        onClick = {
                            monthTransitionAxis = MonthSwipeAxis.Horizontal
                            viewModel.goToMonth(YearMonth.now())
                        },
                    )
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
                    .weight(1f)
                    .pointerInput(Unit) {
                        var total = Offset.Zero
                        val threshold = 56.dp.toPx()
                        detectDragGestures(
                            onDragStart = { total = Offset.Zero },
                            onDragEnd = {
                                val swipe = monthSwipe(total, threshold)
                                if (swipe != null) monthTransitionAxis = swipe.axis
                                when (swipe?.direction) {
                                    MonthSwipeDirection.Next -> viewModel.nextMonth()
                                    MonthSwipeDirection.Previous -> viewModel.previousMonth()
                                    null -> Unit
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                total += dragAmount
                            },
                        )
                    },
            ) {
                AnimatedContent(
                    targetState = state.visibleMonth,
                    transitionSpec = {
                        val direction = if (monthTransitionAxis == MonthSwipeAxis.Vertical) {
                            if (targetState > initialState) {
                                AnimatedContentTransitionScope.SlideDirection.Up
                            } else {
                                AnimatedContentTransitionScope.SlideDirection.Down
                            }
                        } else {
                            if (targetState > initialState) {
                                AnimatedContentTransitionScope.SlideDirection.Start
                            } else {
                                AnimatedContentTransitionScope.SlideDirection.End
                            }
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
private fun MonthTitle(month: YearMonth, onClick: () -> Unit) {
    val monthName = month.month.getDisplayName(TextStyle.FULL, currentLocale())
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedLetters(
            text = monthName,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))
        AnimatedLetters(
            text = month.year.toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun MonthJumpDialog(
    initialMonth: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    var year by remember(initialMonth) { mutableStateOf(initialMonth.year) }
    val monthRows = remember { java.time.Month.entries.chunked(3) }
    val locale = currentLocale()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { year-- }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year")
                }
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { year++ }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                monthRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { month ->
                            val selected = year == initialMonth.year && month == initialMonth.month
                            Surface(
                                onClick = { onSelect(YearMonth.of(year, month)) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        month.getDisplayName(TextStyle.SHORT, locale),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(YearMonth.of(year, initialMonth.month)) }) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Renders [text] as individually animated glyphs: when the string changes, characters that stay
 * the same at a given position hold still while the ones that differ roll over, staggered left to
 * right. The row animates its own width so a shorter or longer month name glides the year across.
 */
@Composable
private fun AnimatedLetters(text: String, color: Color, fontWeight: FontWeight) {
    Row(modifier = Modifier.animateContentSize(tween(Motion.DurationMedium))) {
        text.forEachIndexed { index, ch ->
            key(index) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val delay = index * 28
                        (slideInVertically(tween(Motion.DurationMedium, delayMillis = delay)) { it / 2 } +
                            fadeIn(tween(Motion.DurationMedium, delayMillis = delay))) togetherWith
                            (slideOutVertically(tween(Motion.DurationMedium)) { -it / 2 } +
                                fadeOut(tween(Motion.DurationMedium)))
                    },
                    label = "letter",
                ) { c ->
                    Text(
                        c.toString(),
                        fontFamily = BricolageFamily,
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                        fontWeight = fontWeight,
                        letterSpacing = (-0.01).em,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
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
                            text = date.format(rememberDateFormatter("EEE, MMM d")),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = if (events.isEmpty()) "No events" else {
                                "${events.size} event${if (events.size == 1) "" else "s"}"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (events.isEmpty()) {
                    Text(
                        "Tap + to add something to this day.",
                        style = MaterialTheme.typography.bodyLarge,
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
            text = previewTimeLabel(event, LocalUse24HourClock.current, currentLocale()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.size(width = 62.dp, height = 22.dp),
        )
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun previewTimeLabel(event: Event, is24Hour: Boolean, locale: Locale): String =
    if (event.allDay) {
        "All day"
    } else {
        event.start.atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(timeFormatter(is24Hour, locale))
    }

internal enum class MonthSwipeDirection {
    Previous,
    Next,
}

internal enum class MonthSwipeAxis {
    Horizontal,
    Vertical,
}

internal data class MonthSwipe(
    val direction: MonthSwipeDirection,
    val axis: MonthSwipeAxis,
)

internal fun monthSwipe(totalDrag: Offset, threshold: Float): MonthSwipe? {
    val absX = kotlin.math.abs(totalDrag.x)
    val absY = kotlin.math.abs(totalDrag.y)
    return when {
        absX >= absY && absX >= threshold -> {
            MonthSwipe(
                direction = if (totalDrag.x < 0f) MonthSwipeDirection.Next else MonthSwipeDirection.Previous,
                axis = MonthSwipeAxis.Horizontal,
            )
        }
        absY > absX && absY >= threshold -> {
            MonthSwipe(
                direction = if (totalDrag.y < 0f) MonthSwipeDirection.Next else MonthSwipeDirection.Previous,
                axis = MonthSwipeAxis.Vertical,
            )
        }
        else -> null
    }
}

@Composable
private fun WeekHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        val locale = currentLocale()
        val labels = remember(locale) { Dates.weekStartLabels(locale) }
        labels.forEachIndexed { index, label ->
            Text(
                label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (index >= 5) {
                    app.foscal.core.ui.theme.weekendLabelColor()
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
    // Today reads as a filled accent disc; a tapped (but not-today) day gets a soft tonal disc so
    // both states are legible without competing with the accent. Today keeps an animated fill so it
    // eases in/out as months slide past; selection is a direct tap, so it snaps instantly — animating
    // it would leave the previously selected day glowing through the crossfade.
    val todayDisc by animateColorAsState(
        targetValue = if (isToday) primary else Color.Transparent,
        animationSpec = tween(Motion.DurationMedium),
        label = "todayDisc",
    )
    val discColor = when {
        isToday -> todayDisc
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val numberColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> dayNumberColor
    }
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(discColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    fontFamily = BricolageFamily,
                    fontSize = 15.5.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = numberColor,
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
    // Keep the dot size fixed and small enough that the cell content fits even in a six-row month;
    // otherwise Compose squeezes the overflowing dots and they render smaller in taller months than
    // in shorter ones. See DayCell's vertical padding, which is tuned to leave room for this row.
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .height(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        events.forEach { event ->
            val color = Color(event.color).copy(alpha = lerpFloat(0.4f, 1f, f))
            Canvas(modifier = Modifier.size(5.dp)) {
                drawCircle(color = color)
            }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

internal fun visibleMonthCells(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
): List<LocalDate> {
    val first = month.atDay(1)
    val offset = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val origin = first.minusDays(offset.toLong())
    // Only render as many whole weeks as the month actually spans (4, 5, or 6 rows) rather than
    // padding every month to a fixed six-week grid.
    val weeks = ((offset + month.lengthOfMonth()) + 6) / 7
    return (0 until weeks * 7).map { origin.plusDays(it.toLong()) }
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
