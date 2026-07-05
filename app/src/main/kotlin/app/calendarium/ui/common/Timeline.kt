package app.calendarium.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calendarium.core.model.Event
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TimelineDay(
    val date: LocalDate,
    val events: List<Event>,
)

@Composable
fun TimelineLayout(
    days: List<TimelineDay>,
    onEventClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    hourHeight: Dp = 56.dp,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val totalHeight = hourHeight * 24
    val today = LocalDate.now(zone)
    val nowZ = now.atZone(zone)
    val nowFractionalHour = nowZ.hour + nowZ.minute / 60f
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.error
    val allDayEvents = days.flatMap { day -> day.events.filter { it.allDay } }
    val timedDays = days.map { day -> day.copy(events = day.events.filter { !it.allDay }) }

    var initialScrolled = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialScrolled.value) {
            initialScrolled.value = true
            val targetPx = with(density) { (hourHeight * 6).toPx() }.toInt()
            scrollState.scrollTo(targetPx.coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // All-day strip
        if (allDayEvents.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(52.dp))
                days.forEach { day ->
                    val dayAllDay = day.events.filter { it.allDay }.sortedBy { it.start }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp, vertical = 4.dp),
                    ) {
                        dayAllDay.take(2).forEach { event ->
                            AllDayChip(
                                event = event,
                                onClick = { onEventClick(event.id) },
                            )
                        }
                        if (dayAllDay.size > 2) {
                            Text(
                                "+${dayAllDay.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 1.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = gridColor,
                thickness = 0.5.dp,
            )
            Spacer(Modifier.height(4.dp))
        }

        // Timeline
        Column(modifier = Modifier.verticalScroll(scrollState)) {
            Row(modifier = Modifier.height(totalHeight)) {
                // Hour gutter
                Column(Modifier.width(52.dp)) {
                    for (h in 0..23) {
                        Box(
                            Modifier
                                .height(hourHeight)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Text(
                                "${"%02d".format(h)}",
                                modifier = Modifier.padding(end = 6.dp, top = 0.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                // Day columns
                timedDays.forEach { day ->
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        val colWidth = maxWidth
                        // Grid lines
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
                        // Now line on today
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
                        // Events
                        val positioned = remember(day.events, hourHeight, zone) {
                            layoutTimed(day.events, hourHeight, zone)
                        }
                        positioned.forEach { pe ->
                            val eachWidth = (colWidth / pe.columnCount) - 2.dp
                            EventBlock(
                                event = pe.event,
                                zone = zone,
                                modifier = Modifier
                                    .offset(x = eachWidth * pe.column + 1.dp, y = pe.topDp)
                                    .width(eachWidth)
                                    .height(pe.heightDp),
                                onClick = { onEventClick(pe.event.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDayChip(event: Event, onClick: () -> Unit) {
    val baseColor = paletteColor(event)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(baseColor.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(baseColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            event.title,
            style = MaterialTheme.typography.labelSmall,
            color = baseColor,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val baseColor = paletteColor(event)
    val shape = RoundedCornerShape(8.dp)
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    Row(
        modifier = modifier
            .clip(shape)
            .background(baseColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(baseColor),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, end = 4.dp, top = 3.dp),
        ) {
            Text(
                event.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
            Text(
                "${start.toLocalTime().format(timeFmt)} – ${end.toLocalTime().format(timeFmt)}",
                style = MaterialTheme.typography.labelSmall,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

private val eventPalette = intArrayOf(
    0xFF1976D2.toInt(), 0xFFD81B60.toInt(), 0xFF43A047.toInt(),
    0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt(),
    0xFFE53935.toInt(), 0xFF5C6BC0.toInt(),
)

private fun paletteColor(event: Event): Color {
    val key = (event.id + event.calendarId).toInt()
    return Color(eventPalette[((key % eventPalette.size) + eventPalette.size) % eventPalette.size])
}

private data class PositionedEvent(
    val event: Event,
    val column: Int,
    val columnCount: Int,
    val topDp: Dp,
    val heightDp: Dp,
)

private fun layoutTimed(
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
            val endFrac = (endZ.hour + endZ.minute / 60f + endZ.second / 3600f)
                .coerceIn(startFrac + 0.25f, 24f)
            val top = hourHeight * startFrac
            val height = (hourHeight * (endFrac - startFrac)).coerceAtLeast(28.dp)
            out.add(PositionedEvent(e, col, totalCols, top, height))
        }
    }
    return out
}
