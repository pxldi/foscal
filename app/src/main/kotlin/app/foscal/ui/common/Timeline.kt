package app.foscal.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foscal.core.model.Event
import app.foscal.ui.contrastColor
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.timeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/**
 * Width of the hour-label gutter and the inset at the far edge of the grid. Any header rendered
 * above a [TimelineLayout] must use the same two values, or its weekday columns drift out of
 * alignment with the grid columns underneath — the drift accumulates across the week and is most
 * visible on the last day.
 */
val TimelineGutterWidth = 54.dp
val TimelineEndInset = 4.dp

/** Grid hour to open on when no timed event and no "now" marker gives a better anchor. */
private const val DEFAULT_ANCHOR_HOUR = 8

/** Context kept above the anchor so the marker or first event isn't flush against the top edge. */
private const val ANCHOR_LEAD_IN_HOURS = 1

/**
 * Hour the grid should be scrolled to for [days]: the current hour when today is on screen,
 * otherwise the first timed event of the shown days, otherwise [DEFAULT_ANCHOR_HOUR].
 *
 * [days] must already be filtered to timed events — all-day events live in their own header and
 * carry no meaningful hour.
 */
internal fun anchorHour(
    days: List<TimelineDay>,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
): Int {
    val focus = if (days.any { it.date == today }) {
        now.atZone(zone).hour
    } else {
        val shownDates = days.map { it.date }.toSet()
        days.asSequence()
            .flatMap { it.events }
            .map { it.start.atZone(zone) }
            // A multi-day event starting before this view begins would otherwise drag the anchor
            // back to its original start hour on an unrelated day.
            .filter { it.toLocalDate() in shownDates }
            .minOfOrNull { it.hour }
            ?: DEFAULT_ANCHOR_HOUR
    }
    return (focus - ANCHOR_LEAD_IN_HOURS).coerceIn(0, 23)
}

data class TimelineDay(
    val date: LocalDate,
    val events: List<Event>,
)

/**
 * Hour-grid timeline with positioned event blocks.
 *
 * @param compact When true (week view), event blocks use only the title and tight
 *   padding. When false (day view), blocks show title + time + optional location with
 *   relaxed padding and an accent stripe.
 */
@Composable
fun TimelineLayout(
    days: List<TimelineDay>,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
    hourHeight: Dp = 60.dp,
    compact: Boolean = false,
    // Remembered, not a bare Instant.now(): as a default argument it would be re-evaluated on
    // every recomposition, restarting the marker ticker below before its delay ever elapsed.
    now: Instant = remember { Instant.now() },
    zone: ZoneId = ZoneId.systemDefault(),
    blockCornerRadius: Dp = if (compact) 5.dp else 7.dp,
    accentStripe: Boolean = !compact,
    onTimeRangeSelected: ((startMillis: Long, endMillis: Long) -> Unit)? = null,
    onEventMove: ((event: Event, newStartMillis: Long, newEndMillis: Long) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val totalHeight = hourHeight * 24
    // Advance the current-time marker while the view stays open instead of freezing it at the
    // instant this composable first ran.
    val liveNowState = remember(now) { mutableStateOf(now) }
    val liveNow by liveNowState
    LaunchedEffect(now) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            liveNowState.value = Instant.now()
        }
    }
    val today = LocalDate.now(zone)
    val nowZ = liveNow.atZone(zone)
    val nowFractionalHour = nowZ.hour + nowZ.minute / 60f
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.error
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val allDayEvents = days.flatMap { day -> day.events.filter { it.allDay } }
    val timedDays = days.map { day -> day.copy(events = day.events.filter { !it.allDay }) }
    // Only worth tinting a whole column when several are shown side by side (week view); in day
    // view the single column fills the screen so a tint just muddies the background.
    val highlightTodayColumn = days.size > 1
    val showNowLabel = timedDays.any { it.date == today }
    val is24Hour = LocalUse24HourClock.current
    val nowLabelFmt = remember(is24Hour) { timeFormatter(is24Hour) }
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
    var eventDrag by remember { mutableStateOf<EventDrag?>(null) }

    // Open on the part of the day the user cares about. A fixed early-morning offset means that
    // opening the app in the afternoon shows an empty grid with the next event scrolled off below.
    val anchorHour = anchorHour(timedDays, today, now, zone)
    LaunchedEffect(anchorHour) {
        val targetPx = with(density) { (hourHeight * anchorHour).toPx() }.toInt()
        scrollState.scrollTo(targetPx.coerceAtLeast(0))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (allDayEvents.isNotEmpty()) {
            AllDayHeader(days = days, onEventClick = onEventClick)
            HorizontalDivider(
                modifier = Modifier.padding(start = TimelineGutterWidth, end = TimelineEndInset),
                color = gridColor,
                thickness = 0.5.dp,
            )
            Spacer(Modifier.height(4.dp))
        }

        Column(modifier = Modifier.verticalScroll(scrollState)) {
            Box {
                Row(
                    modifier = Modifier
                        .height(totalHeight)
                        .padding(end = TimelineEndInset),
                ) {
                    Column(Modifier.width(TimelineGutterWidth)) {
                        for (h in 0..23) {
                            Box(
                                Modifier
                                    .height(hourHeight)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.TopEnd,
                            ) {
                                Text(
                                    "${"%02d".format(h)}",
                                    modifier = Modifier.padding(end = 8.dp),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    timedDays.forEachIndexed { dayIndex, day ->
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (onTimeRangeSelected != null) {
                                        Modifier.pointerInput(day.date, hourHeight, onTimeRangeSelected) {
                                            fun minuteAt(y: Float): Int {
                                                val raw = (y / hourHeight.toPx() * 60f).toInt()
                                                return raw.roundToStep(15).coerceIn(0, 24 * 60)
                                            }

                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    val minute = minuteAt(offset.y)
                                                    selection = TimeSelection(day.date, minute, minute)
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val start = selection ?: return@detectDragGesturesAfterLongPress
                                                    selection = start.copy(endMinute = minuteAt(change.position.y))
                                                },
                                                onDragCancel = { selection = null },
                                                onDragEnd = {
                                                    val finalSelection = selection
                                                    selection = null
                                                    if (finalSelection != null) {
                                                        val range = finalSelection.normalized()
                                                        val startMinute = range.first
                                                        val endMinute = when {
                                                            range.second > range.first -> range.second
                                                            range.first <= 23 * 60 -> range.first + 60
                                                            else -> 24 * 60
                                                        }
                                                        val start = finalSelection.date.atStartOfDay(zone)
                                                            .plusMinutes(startMinute.toLong())
                                                        val end = finalSelection.date.atStartOfDay(zone)
                                                            .plusMinutes(endMinute.toLong())
                                                        onTimeRangeSelected(
                                                            start.toInstant().toEpochMilli(),
                                                            end.toInstant().toEpochMilli(),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (highlightTodayColumn && day.date == today) {
                                        Modifier.background(todayTint)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            val colWidth = maxWidth
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val hourPx = hourHeight.toPx()
                                for (h in 1..23) {
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, h * hourPx),
                                        end = Offset(size.width, h * hourPx),
                                        strokeWidth = 0.5f,
                                    )
                                }
                            }
                            if (day.date == today) {
                                val nowY = nowFractionalHour * with(density) { hourHeight.toPx() }
                                Canvas(Modifier.fillMaxSize()) {
                                    drawLine(
                                        color = nowColor,
                                        start = Offset(0f, nowY),
                                        end = Offset(size.width, nowY),
                                        strokeWidth = 1.5f,
                                    )
                                    drawCircle(
                                        color = nowColor,
                                        radius = 4.5f,
                                        center = Offset(0f, nowY),
                                    )
                                }
                            }
                            selection
                                ?.takeIf { it.date == day.date }
                                ?.let { current ->
                                    val range = current.normalized()
                                    val top = hourHeight * (range.first / 60f)
                                    val height = (hourHeight * ((range.second - range.first).coerceAtLeast(15) / 60f))
                                        .coerceAtLeast(18.dp)
                                    Box(
                                        modifier = Modifier
                                            .offset(y = top)
                                            .fillMaxWidth()
                                            .height(height)
                                            .padding(horizontal = 3.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                    )
                                }
                            val positioned = remember(day.events, hourHeight, zone) {
                                layoutTimed(day.events, hourHeight, zone)
                            }
                            positioned.forEach { pe ->
                                val eachWidth = (colWidth / pe.columnCount) - 2.dp
                                val drag = eventDrag?.takeIf {
                                    it.eventId == pe.event.id &&
                                        it.instanceStartMillis == pe.event.start.toEpochMilli()
                                }
                                EventBlock(
                                    event = pe.event,
                                    zone = zone,
                                    heightDp = pe.heightDp,
                                    compact = compact,
                                    accentStripe = accentStripe,
                                    cornerRadius = blockCornerRadius,
                                    modifier = Modifier
                                        .offset(
                                            x = eachWidth * pe.column + 1.dp +
                                                colWidth * (drag?.deltaDays ?: 0),
                                            y = pe.topDp + hourHeight * ((drag?.deltaMinutes ?: 0) / 60f),
                                        )
                                        .width(eachWidth)
                                        .height(pe.heightDp),
                                    onClick = { onEventClick(pe.event.id, pe.event.start.toEpochMilli()) },
                                    onMove = onEventMove?.let { move ->
                                        { deltaDays, deltaMinutes ->
                                            val start = pe.event.start.atZone(zone)
                                                .plusDays(deltaDays.toLong())
                                                .plusMinutes(deltaMinutes.toLong())
                                            val end = pe.event.end.atZone(zone)
                                                .plusDays(deltaDays.toLong())
                                                .plusMinutes(deltaMinutes.toLong())
                                            move(
                                                pe.event,
                                                start.toInstant().toEpochMilli(),
                                                end.toInstant().toEpochMilli(),
                                            )
                                        }
                                    },
                                    onMovePreview = if (onEventMove != null) {
                                        { deltaDays, deltaMinutes ->
                                            eventDrag = EventDrag(
                                                eventId = pe.event.id,
                                                instanceStartMillis = pe.event.start.toEpochMilli(),
                                                deltaDays = deltaDays,
                                                deltaMinutes = deltaMinutes,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onMovePreviewEnd = { eventDrag = null },
                                    dayIndex = dayIndex,
                                    visibleDayCount = timedDays.size,
                                    eventLeftInDay = eachWidth * pe.column + 1.dp,
                                    dayWidth = colWidth,
                                    hourHeight = hourHeight,
                                )
                            }
                        }
                    }
                }
                // Current-time label pinned to the left gutter, aligned with the now line drawn in
                // the day columns. Overlaid on the Row so it lines up across the shared scale.
                if (showNowLabel) {
                    Box(
                        Modifier
                            .width(54.dp)
                            .offset(y = hourHeight * nowFractionalHour - 8.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            nowZ.format(nowLabelFmt),
                            modifier = Modifier
                                .padding(end = 5.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(nowColor)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private data class TimeSelection(
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
) {
    fun normalized(): IntRangeLike =
        if (startMinute <= endMinute) {
            IntRangeLike(startMinute, endMinute)
        } else {
            IntRangeLike(endMinute, startMinute)
        }
}

private data class IntRangeLike(val first: Int, val second: Int)

private data class EventDrag(
    val eventId: Long,
    val instanceStartMillis: Long,
    val deltaDays: Int,
    val deltaMinutes: Int,
)

private fun Int.roundToStep(step: Int): Int {
    val half = step / 2
    return ((this + half) / step) * step
}

/** An all-day event and the inclusive range of visible day columns it covers. */
internal data class AllDaySpan(val event: Event, val firstCol: Int, val lastCol: Int)

/**
 * All-day / multi-day header. Each event is drawn as a single bar spanning every visible day
 * column it covers, instead of a repeated (and truncated) chip per day. Overlapping events are
 * packed into stacked lanes the way a week grid does.
 */
@Composable
private fun AllDayHeader(
    days: List<TimelineDay>,
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
) {
    val lanes = remember(days) { assignAllDayLanes(computeAllDaySpans(days)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp, end = TimelineEndInset),
    ) {
        Spacer(Modifier.width(TimelineGutterWidth))
        BoxWithConstraints(Modifier.weight(1f)) {
            val colWidth = maxWidth / days.size.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lanes.forEach { lane ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp),
                    ) {
                        lane.forEach { span ->
                            AllDayBar(
                                event = span.event,
                                modifier = Modifier
                                    .offset(x = colWidth * span.firstCol)
                                    .width(colWidth * (span.lastCol - span.firstCol + 1))
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp),
                                onClick = {
                                    onEventClick(span.event.id, span.event.start.toEpochMilli())
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapse the per-day event lists back into one span per event. Keyed by (id, instance start) so a
 * genuine multi-day event (same instance repeated across days) becomes one wide bar, while a daily
 * recurring all-day event (distinct instances sharing an id) stays one bar per day.
 */
internal fun computeAllDaySpans(days: List<TimelineDay>): List<AllDaySpan> {
    val first = LinkedHashMap<Pair<Long, Long>, Int>()
    val last = HashMap<Pair<Long, Long>, Int>()
    val event = HashMap<Pair<Long, Long>, Event>()
    days.forEachIndexed { idx, day ->
        day.events.filter { it.allDay }.forEach { e ->
            val key = e.id to e.start.toEpochMilli()
            first.putIfAbsent(key, idx)
            last[key] = idx
            event[key] = e
        }
    }
    return first.keys
        .map { key -> AllDaySpan(event.getValue(key), first.getValue(key), last.getValue(key)) }
        .sortedWith(compareBy({ it.firstCol }, { -(it.lastCol - it.firstCol) }))
}

/** Greedy interval partitioning: place each span in the first lane where it doesn't overlap. */
internal fun assignAllDayLanes(spans: List<AllDaySpan>): List<List<AllDaySpan>> {
    val lanes = mutableListOf<MutableList<AllDaySpan>>()
    for (span in spans) {
        val lane = lanes.firstOrNull { existing ->
            existing.none { it.firstCol <= span.lastCol && span.firstCol <= it.lastCol }
        }
        if (lane != null) lane.add(span) else lanes.add(mutableListOf(span))
    }
    return lanes
}

@Composable
private fun AllDayBar(event: Event, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(paletteColor(event))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.title,
            style = MaterialTheme.typography.labelSmall,
            color = contrastColor(event.color),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventBlock(
    event: Event,
    zone: ZoneId,
    heightDp: Dp,
    compact: Boolean,
    accentStripe: Boolean,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMove: ((deltaDays: Int, deltaMinutes: Int) -> Unit)? = null,
    onMovePreview: ((deltaDays: Int, deltaMinutes: Int) -> Unit)? = null,
    onMovePreviewEnd: () -> Unit = {},
    dayIndex: Int = 0,
    visibleDayCount: Int = 1,
    eventLeftInDay: Dp = 0.dp,
    dayWidth: Dp = 0.dp,
    hourHeight: Dp = 60.dp,
) {
    val baseColor = paletteColor(event)
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    val is24Hour = LocalUse24HourClock.current
    val timeFmt = remember(is24Hour) { timeFormatter(is24Hour) }
    val showTime = !compact && heightDp >= 36.dp
    val textPadding = if (compact) {
        // Week columns are only ~48dp wide on a phone. Every dp of padding here costs a character,
        // and once a word no longer fits the line the layout breaks it mid-word ("plannin/g").
        Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 2.dp)
    } else {
        Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
    }
    val titleScale = if (compact) 10.sp else 13.sp
    val detailScale = if (compact) 10.sp else 11.sp
    val maxTitleLines = if (compact) 3 else 2

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(baseColor.copy(alpha = if (compact) 0.12f else 0.10f))
            .then(
                if (onMove != null) {
                    Modifier.pointerInput(event.id, event.start, eventLeftInDay, dayWidth, hourHeight) {
                        var totalDrag = Offset.Zero
                        fun deltas(): Pair<Int, Int> {
                            val rawMinutes = (totalDrag.y / hourHeight.toPx() * 60f).toInt()
                            val deltaMinutes = rawMinutes.roundToStep(15)
                            val widthPx = dayWidth.toPx().takeIf { it > 0f } ?: return 0 to deltaMinutes
                            val centerInWeek = eventLeftInDay.toPx() + size.width / 2f + totalDrag.x
                            val relativeDay = floor(centerInWeek / widthPx).toInt()
                            val targetDay = (dayIndex + relativeDay).coerceIn(0, visibleDayCount - 1)
                            return (targetDay - dayIndex) to deltaMinutes
                        }

                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                totalDrag = Offset.Zero
                                onMovePreview?.invoke(0, 0)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                                val (deltaDays, deltaMinutes) = deltas()
                                onMovePreview?.invoke(deltaDays, deltaMinutes)
                            },
                            onDragCancel = {
                                totalDrag = Offset.Zero
                                onMovePreviewEnd()
                            },
                            onDragEnd = {
                                val (deltaDays, deltaMinutes) = deltas()
                                totalDrag = Offset.Zero
                                onMovePreviewEnd()
                                if (deltaDays != 0 || deltaMinutes != 0) {
                                    onMove(deltaDays, deltaMinutes)
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (accentStripe) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(baseColor),
            )
        }
        Column(textPadding) {
            Text(
                event.title,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                fontSize = titleScale,
                maxLines = maxTitleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (showTime) {
                Text(
                    "${start.toLocalTime().format(timeFmt)} – ${end.toLocalTime().format(timeFmt)}",
                    color = mutedTextColor,
                    fontSize = detailScale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun paletteColor(event: Event): Color = Color(event.color)

internal data class PositionedEvent(
    val event: Event,
    val column: Int,
    val columnCount: Int,
    val topDp: Dp,
    val heightDp: Dp,
)

internal fun layoutTimed(
    events: List<Event>,
    hourHeight: Dp,
    zone: ZoneId,
): List<PositionedEvent> {
    if (events.isEmpty()) return emptyList()
    val sorted = events.sortedBy { it.start }
    val clusters = mutableListOf<MutableList<Event>>()
    var clusterEnd = Long.MIN_VALUE
    for (e in sorted) {
        if (clusters.isEmpty() || e.start.toEpochMilli() >= clusterEnd) {
            clusters.add(mutableListOf(e))
            clusterEnd = e.end.toEpochMilli()
        } else {
            clusters.last().add(e)
            clusterEnd = maxOf(clusterEnd, e.end.toEpochMilli())
        }
    }
    val out = mutableListOf<PositionedEvent>()
    for (cluster in clusters) {
        val columnEnds = mutableListOf<Long>()
        val eventColumns = mutableListOf<Pair<Event, Int>>()
        for (e in cluster.sortedBy { it.start }) {
            var placed = false
            for (i in columnEnds.indices) {
                if (columnEnds[i] <= e.start.toEpochMilli()) {
                    columnEnds[i] = e.end.toEpochMilli()
                    eventColumns.add(e to i)
                    placed = true
                    break
                }
            }
            if (!placed) {
                columnEnds.add(e.end.toEpochMilli())
                eventColumns.add(e to columnEnds.size - 1)
            }
        }
        val totalCols = columnEnds.size
        for ((e, col) in eventColumns) {
            val startZ = e.start.atZone(zone)
            val endZ = e.end.atZone(zone)
            val startFrac = (startZ.hour + startZ.minute / 60f + startZ.second / 3600f)
                .coerceIn(0f, 24f)
            // Keep a minimum visible slice, but never let the lower bound exceed 24h — an event
            // starting after 23:45 would otherwise make coerceIn's range empty and crash.
            val endFrac = (endZ.hour + endZ.minute / 60f + endZ.second / 3600f)
                .coerceIn((startFrac + 0.25f).coerceAtMost(24f), 24f)
            val top = hourHeight * startFrac
            val height = (hourHeight * (endFrac - startFrac)).coerceAtLeast(32.dp)
            out.add(PositionedEvent(e, col, totalCols, top, height))
        }
    }
    return out
}
