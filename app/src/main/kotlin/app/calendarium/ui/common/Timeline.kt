package app.calendarium.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    val initialScrolled = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialScrolled.value) {
            initialScrolled.value = true
            val targetPx = with(density) { (hourHeight * 6).toPx() }.toInt()
            scrollState.scrollTo(targetPx.coerceAtLeast(0))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(top = 4.dp),
    ) {
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
                            "%02d:00".format(h),
                            modifier = Modifier.padding(end = 6.dp, top = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Day columns
            days.forEach { day ->
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    val colWidth = maxWidth
                    // grid lines
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
                    // now line on today
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
                                radius = 4f,
                                center = Offset(0f, nowY),
                            )
                        }
                    }
                    // events
                    val positioned = remember(day.events, hourHeight, zone) {
                        layoutEvents(day.events, hourHeight, zone)
                    }
                    positioned.forEach { pe ->
                        val eachWidth = (colWidth / pe.columnCount) - 2.dp
                        EventBlock(
                            event = pe.event,
                            zone = zone,
                            modifier = Modifier
                                .offset(
                                    x = eachWidth * pe.column + 1.dp,
                                    y = pe.topDp,
                                )
                                .width(eachWidth)
                                .height(pe.heightDp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onEventClick(pe.event.id) },
                        )
                    }
                }
            }
        }
        Spacer(8.dp)
    }
}

@Composable
private fun EventBlock(
    event: Event,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val palette = listOf(
        0xFF1976D2.toInt(), 0xFFD81B60.toInt(), 0xFF43A047.toInt(),
        0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt(),
    )
    val key = (event.id + event.calendarId).toInt()
    val baseColor = Color(palette[((key % palette.size) + palette.size) % palette.size])
    val containerColor = baseColor.copy(alpha = 0.18f)
    Row(
        modifier = modifier.background(containerColor),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(baseColor),
        )
        Text(
            text = event.title,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            color = baseColor,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Spacer(height: Dp) {
    Box(Modifier.height(height))
}

private data class PositionedEvent(
    val event: Event,
    val column: Int,
    val columnCount: Int,
    val topDp: Dp,
    val heightDp: Dp,
)

private fun layoutEvents(
    events: List<Event>,
    hourHeight: Dp,
    zone: ZoneId,
): List<PositionedEvent> {
    val timed = events.filter { !it.allDay }
    if (timed.isEmpty()) return emptyList()
    val sorted = timed.sortedBy { it.start }
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
                .coerceIn(startFrac + 0.15f, 24f)
            val top = hourHeight * startFrac
            val height = (hourHeight * (endFrac - startFrac)).coerceAtLeast(20.dp)
            out.add(PositionedEvent(e, col, totalCols, top, height))
        }
    }
    return out
}
