package app.foscal.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.foscal.core.model.Event
import app.foscal.core.ui.theme.LocalIsDarkTheme
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.eventColors
import app.foscal.ui.util.LocalEventTextScale
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.LocalWrapEventTitles
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.scaledBy
import app.foscal.ui.util.timeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Width of the hour-label gutter and the inset at the far edge of the grid. Any header rendered
 * above a [TimelineLayout] must use the same two values, or its weekday columns drift out of
 * alignment with the grid columns underneath — the drift accumulates across the week and is most
 * visible on the last day.
 */
val TimelineGutterWidth = 54.dp

/**
 * Taken off the bottom of every block so touching events keep a visible seam.
 *
 * Off the drawn height rather than out of the layout: the block still owns its slot, so the grid
 * and the times stay honest and only the paint stops short.
 */
private val BlockGap = 3.dp

/**
 * The shortest a block may be drawn, whatever its length says.
 *
 * A ten-minute event is four dp on a phone-sized grid, which is not enough to put a word in.
 */
private val MinBlockHeight = 16.dp

/** Half the height of the now label, which is centred on the now line. */
private val NowLabelClearance = 8.dp

/** The height of an 11sp hour label, which hangs below its grid line. */
private val HourLabelHeight = 16.dp

val TimelineEndInset = 4.dp

/**
 * What a drag or a tap on an empty part of the grid snaps to.
 *
 * Ten minutes rather than a quarter of an hour: a stand-up, a call and a school run are all things
 * people book in tens, and at any sensible hour height ten minutes is still several dp — enough
 * that the block visibly answers the finger rather than sticking and then jumping.
 */
private const val SnapMinutes = 10

/**
 * What a *tap* snaps to, which is not the same thing.
 *
 * A drag is someone drawing a block and saying exactly how long it is; a tap is someone pointing
 * at a part of the day and letting the app pick. Nobody points at ten past. The hour is what "here"
 * means when you have not said otherwise.
 */
private const val TapSnapMinutes = 60

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
    /** How long a block placed by a tap — or by a long press that never moved — comes out. */
    newEventMinutes: Int = 60,
    /**
     * Bumped by the caller when a move it was handed is not going to happen after all, so the
     * block being held at the dropped position can go back where it came from.
     */
    revertMoveSignal: Int = 0,
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
    // The hour lines are a ruler, not a border: they only have to be findable when you look for
    // them. A solid outline colour turns the grid into the loudest thing on an empty day. Ink in
    // light, white in dark — a dark grey line on a dark background reads as dirt.
    val hourLineColor = if (LocalIsDarkTheme.current) {
        Color.White.copy(alpha = 0.17f)
    } else {
        Color.Black.copy(alpha = 0.11f)
    }
    val nowColor = MaterialTheme.colorScheme.error
    val todayTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val allDayEvents = days.flatMap { day -> day.events.filter { it.allDay } }
    val timedDays = days.map { day -> day.copy(events = day.events.filter { !it.allDay }) }
    // Only worth tinting a whole column when several are shown side by side (week view); in day
    // view the single column fills the screen so a tint just muddies the background.
    val highlightTodayColumn = days.size > 1
    val showNowLabel = timedDays.any { it.date == today }
    val is24Hour = LocalUse24HourClock.current
    val locale = currentLocale()
    val nowLabelFmt = remember(is24Hour, locale) { timeFormatter(is24Hour, locale) }
    // The gutter is 54dp. "10:40 PM" in bold did not fit it, and "12 PM" is as much as an hour
    // label needs; the now line and the labels around it already say which half of the day it is.
    val hourLabelFmt = remember(is24Hour, locale) {
        java.time.format.DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h a", locale)
    }
    val gutterNowFmt = remember(is24Hour, locale) {
        java.time.format.DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm", locale)
    }
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
    // Where a tap has parked a new-event block, waiting for a second tap to open the editor. Held
    // here rather than in the day column so tapping another day moves the one block instead of
    // leaving a trail of them behind.
    var placement by remember { mutableStateOf<NewEventPlacement?>(null) }
    // A long press reaches the tap detector too, as a very slow tap, and would park a block on top
    // of the event the drag just created. Set when the press becomes a drag and cleared at the
    // start of every press, so it only ever suppresses the tap belonging to that same gesture.
    var longPressActive by remember { mutableStateOf(false) }
    // Every gesture on this grid produces something the finger is covering: a block under the
    // thumb, a snap the eye cannot resolve mid-drag, a parked placeholder that lands on the hour
    // rather than where the tap was. Touch is the only channel that can confirm any of it without
    // asking the user to move their hand out of the way first.
    val haptics = LocalHapticFeedback.current
    var eventDrag by remember { mutableStateOf<EventDrag?>(null) }
    // The preview outlives the finger. A dropped event is written to the provider and comes back
    // through a flow, which takes a few frames; releasing the preview on lift put the block back
    // where it started for exactly that long, so every successful move read as a jump backwards
    // followed by a jump forwards. Now the block simply stays where it was dropped and the new
    // data replaces it in place.
    LaunchedEffect(days) { if (eventDrag?.committed == true) eventDrag = null }
    // A move that was offered and turned down (the recurring "which of these?" dialog, dismissed)
    // never reaches the provider, so no new data is coming to release the block.
    LaunchedEffect(revertMoveSignal) { if (revertMoveSignal > 0) eventDrag = null }
    // A write the provider refuses emits nothing either; the caller reports that through
    // revertMoveSignal. This timeout is for a write that neither lands nor is refused, since a
    // block stranded where it is not is worse than one that snaps back a moment late.
    LaunchedEffect(eventDrag?.committed) {
        if (eventDrag?.committed != true) return@LaunchedEffect
        delay(4_000)
        eventDrag = null
    }
    // What is pending on the grid right now, as a day and a range of minutes: a drag in progress
    // beats a parked block, since the finger is on the first one.
    val pending: Pair<LocalDate, IntRangeLike>? = selection?.let { it.date to it.span(newEventMinutes) }
        ?: placement?.let { it.date to it.span(newEventMinutes) }
    val pendingLabel = pending?.let { (_, range) ->
        "${minuteLabel(range.first, nowLabelFmt)} – ${minuteLabel(range.second, nowLabelFmt)}"
    }

    // Open on the part of the day the user cares about. A fixed early-morning offset means that
    // opening the app in the afternoon shows an empty grid with the next event scrolled off below.
    val anchorHour = anchorHour(timedDays, today, now, zone)
    // Which anchor has already been applied, saved rather than merely remembered. Opening an event
    // takes the grid out of composition, and a plain `remember` would forget on the way back and
    // re-anchor — throwing away the position the user had scrolled to, which `scrollState` itself
    // restores perfectly well.
    var anchoredAt by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    LaunchedEffect(anchorHour) {
        if (anchoredAt == anchorHour) return@LaunchedEffect
        anchoredAt = anchorHour
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
            BoxWithConstraints {
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
                                // The hour gives way to the current time when the two would land
                                // on each other. "12:07" printed over "12:00" is unreadable, and
                                // of the two the one you already know is the hour. The hour label
                                // hangs below its line and the now label is centred on its own,
                                // so the clearance is wider below the line than above it.
                                val offset = hourHeight * (nowFractionalHour - h)
                                val eclipsed = showNowLabel &&
                                    offset > -NowLabelClearance && offset < HourLabelHeight + NowLabelClearance
                                if (!eclipsed) {
                                    Text(
                                        // The whole time, not just the hour. "05" beside a grid
                                        // line is a label you have to decode; "05:00" is one you
                                        // read.
                                        java.time.LocalTime.of(h, 0).format(hourLabelFmt),
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(end = 10.dp),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                                        Modifier.pointerInput(day.date, hourHeight, newEventMinutes, onTimeRangeSelected) {
                                            // Rounded, not truncated. Truncating biases every edge
                                            // of the drag upward by up to a whole step, so the
                                            // block trails the finger on the way down and leads it
                                            // on the way back — which is what made this feel
                                            // sticky rather than merely coarse.
                                            fun minuteAt(y: Float): Int {
                                                val raw = (y / hourHeight.toPx() * 60f).roundToInt()
                                                return raw.roundToStep(SnapMinutes).coerceIn(0, 24 * 60)
                                            }

                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    longPressActive = true
                                                    placement = null
                                                    val minute = minuteAt(offset.y)
                                                    selection = TimeSelection(day.date, minute, minute)
                                                    // The long press is the moment the gesture
                                                    // changes meaning, and nothing on screen says
                                                    // so until the finger moves.
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress,
                                                    )
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val start = selection ?: return@detectDragGesturesAfterLongPress
                                                    val minute = minuteAt(change.position.y)
                                                    // One tick per step crossed, not per event:
                                                    // the drag reports every pixel and the block
                                                    // only moves every ten minutes, so ticking on
                                                    // movement would buzz continuously and say
                                                    // nothing. An hour of dragging is six ticks.
                                                    if (minute != start.endMinute) {
                                                        haptics.performHapticFeedback(
                                                            HapticFeedbackType.SegmentFrequentTick,
                                                        )
                                                    }
                                                    selection = start.copy(endMinute = minute)
                                                },
                                                onDragCancel = { selection = null },
                                                onDragEnd = {
                                                    val finalSelection = selection
                                                    selection = null
                                                    if (finalSelection != null) {
                                                        val range = finalSelection.span(newEventMinutes)
                                                        val start = finalSelection.date.atStartOfDay(zone)
                                                            .plusMinutes(range.first.toLong())
                                                        val end = finalSelection.date.atStartOfDay(zone)
                                                            .plusMinutes(range.second.toLong())
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
                                // A plain tap parks a block instead of opening the editor. The
                                // block itself carries the click that opens it, so this only ever
                                // sees taps on empty grid — one on an event, or on a parked block,
                                // is consumed before it gets here.
                                .then(
                                    if (onTimeRangeSelected != null) {
                                        Modifier.pointerInput(day.date, hourHeight, newEventMinutes) {
                                            detectTapGestures(
                                                onPress = { longPressActive = false },
                                                onTap = { offset ->
                                                    if (longPressActive) return@detectTapGestures
                                                    selection = null
                                                    // Floored, not rounded: a block starting above
                                                    // where the finger landed reads as a miss.
                                                    val raw = (offset.y / hourHeight.toPx() * 60f).toInt()
                                                    val latest = (24 * 60 - newEventMinutes).coerceAtLeast(0)
                                                    placement = NewEventPlacement(
                                                        day.date,
                                                        raw.floorToStep(TapSnapMinutes).coerceIn(0, latest),
                                                    )
                                                    // The block lands on the hour, which is above
                                                    // where the tap was and often under the hand.
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.Confirm,
                                                    )
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
                                        color = hourLineColor,
                                        start = Offset(0f, h * hourPx),
                                        end = Offset(size.width, h * hourPx),
                                        strokeWidth = 0.5f,
                                    )
                                }
                            }
                            val positioned = remember(day.events, hourHeight, zone) {
                                layoutTimed(day.events, hourHeight, zone)
                            }
                            positioned.forEach { pe ->
                                // Fractions of the day column, not a column index: a nested
                                // event keeps its container's right edge and only gives up ground
                                // on the left.
                                val blockLeft = colWidth * pe.leftFraction + 1.dp
                                val blockWidth = colWidth * pe.widthFraction - 2.dp
                                // A block that reaches past its own end stops at the block that
                                // starts there, instead of running underneath it. Anything that
                                // overhangs is invisible by definition, and centring a title in a
                                // box half of which is covered puts the title in the covered half
                                // — which is what made a ten-minute event unreadable.
                                val overhangs = pe.visibleDp < pe.heightDp
                                val drawnHeight = if (overhangs) {
                                    // Same seam as everywhere else, but with a floor: below about
                                    // a quarter of an hour there is nothing left to give, and a
                                    // line you cannot read or tap is worse than a missing seam.
                                    (pe.visibleDp - BlockGap).coerceAtLeast(10.dp)
                                } else {
                                    (pe.heightDp - BlockGap).coerceAtLeast(12.dp)
                                }
                                val drag = eventDrag?.takeIf {
                                    it.eventId == pe.event.id &&
                                        it.instanceStartMillis == pe.event.start.toEpochMilli()
                                }
                                EventBlock(
                                    event = pe.event,
                                    zone = zone,
                                    heightDp = drawnHeight,
                                    compact = compact,
                                    accentStripe = accentStripe,
                                    cornerRadius = blockCornerRadius,
                                    nested = pe.depth > 0,
                                    modifier = Modifier
                                        .offset(
                                            x = blockLeft + colWidth * (drag?.deltaDays ?: 0),
                                            y = pe.topDp + hourHeight * ((drag?.deltaMinutes ?: 0) / 60f),
                                        )
                                        .width(blockWidth)
                                        // A block is drawn slightly shorter than its slot, so two
                                        // events that touch in time still have a seam between them.
                                        // Without it 08:30-12:00 and 12:00-13:00 render as one
                                        // long shape and the boundary has to be inferred from the
                                        // titles.
                                        .height(drawnHeight),
                                    onClick = { onEventClick(pe.event.id, pe.event.start.toEpochMilli()) },
                                    // A read-only calendar would refuse the write, so its events
                                    // do not lift at all.
                                    onMove = onEventMove?.takeUnless { pe.event.readOnly }?.let { move ->
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
                                    onMovePreview = if (onEventMove != null && !pe.event.readOnly) {
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
                                    onMovePreviewEnd = { committed ->
                                        eventDrag = if (committed) {
                                            eventDrag?.copy(committed = true)
                                        } else {
                                            null
                                        }
                                    },
                                    dayIndex = dayIndex,
                                    visibleDayCount = timedDays.size,
                                    eventLeftInDay = blockLeft,
                                    dayWidth = colWidth,
                                    hourHeight = hourHeight,
                                )
                            }
                            // Over the events, not under them: a range being drawn across a
                            // full day is exactly when you need to see where its edges land.
                            selection
                                ?.takeIf { it.date == day.date }
                                ?.let { current ->
                                    val range = current.span(newEventMinutes)
                                    Box(
                                        modifier = Modifier
                                            .offset(y = hourHeight * (range.first / 60f))
                                            .fillMaxWidth()
                                            .height(range.heightAt(hourHeight))
                                            .padding(horizontal = 3.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                            ),
                                    )
                                }
                            placement
                                ?.takeIf { it.date == day.date }
                                ?.let { spot ->
                                    val range = spot.span(newEventMinutes)
                                    // Keyed on the spot so moving the block to a new hour is a new
                                    // arrival rather than a silent jump: the tap that placed it
                                    // landed somewhere else, and the eye needs telling where it went.
                                    key(spot) {
                                        NewEventPlaceholder(
                                            label = pendingLabel.orEmpty(),
                                            compact = compact,
                                            cornerRadius = blockCornerRadius,
                                            modifier = Modifier
                                                .offset(y = hourHeight * (range.first / 60f))
                                                .fillMaxWidth()
                                                .height(range.heightAt(hourHeight))
                                                .padding(horizontal = 3.dp),
                                            onClick = {
                                                placement = null
                                                val start = spot.date.atStartOfDay(zone)
                                                    .plusMinutes(range.first.toLong())
                                                val end = spot.date.atStartOfDay(zone)
                                                    .plusMinutes(range.second.toLong())
                                                onTimeRangeSelected?.invoke(
                                                    start.toInstant().toEpochMilli(),
                                                    end.toInstant().toEpochMilli(),
                                                )
                                            },
                                        )
                                    }
                                }
                            // Last, so it crosses the blocks instead of hiding behind them. The
                            // whole point of the line is to say where you are in a day that is
                            // mostly full of events; underneath them it only shows in the gaps.
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
                        }
                    }
                }
                // Current-time label pinned to the left gutter, aligned with the now line drawn in
                // the day columns. Overlaid on the Row so it lines up across the shared scale.
                if (showNowLabel) {
                    Box(
                        Modifier
                            .width(TimelineGutterWidth)
                            .offset(y = hourHeight * nowFractionalHour - NowLabelClearance),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        // Coloured text, not a filled chip. The chip was the loudest thing on a
                        // screen whose whole job is the events, and it was shouting the one fact
                        // the user can also read off the clock in their status bar.
                        Text(
                            nowZ.format(gutterNowFmt),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(end = 10.dp),
                            color = nowColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                // What the block being drawn or parked actually covers, in a pill above it.
                // Overlaid on the Row rather than drawn inside the day column, because a week
                // column is about forty-five dp wide and "09:1…" is not a time.
                if (pending != null && pendingLabel != null) {
                    val columnIndex = timedDays.indexOfFirst { it.date == pending.first }
                    if (columnIndex >= 0) {
                        val dayWidth = (maxWidth - TimelineGutterWidth - TimelineEndInset) /
                            timedDays.size.coerceAtLeast(1)
                        // Measured rather than guessed: the label is two times in the user's own
                        // format and locale, which is anywhere from "9 AM – 10 AM" to "09:10 –
                        // 10:20", and a pill that runs off the last column is the one case where
                        // it matters most.
                        var bubbleWidth by remember { mutableStateOf(0.dp) }
                        Box(
                            Modifier
                                .offset(
                                    x = (TimelineGutterWidth + dayWidth * columnIndex)
                                        .coerceAtMost((maxWidth - bubbleWidth - 2.dp).coerceAtLeast(0.dp)),
                                    y = (hourHeight * (pending.second.first / 60f) - 25.dp)
                                        .coerceAtLeast(0.dp),
                                )
                                .onSizeChanged { bubbleWidth = with(density) { it.width.toDp() } }
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Text(
                                pendingLabel,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class TimeSelection(
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
) {
    /**
     * The range this drag actually stands for, in minutes from midnight.
     *
     * A long press that never moved has no range of its own, so it means "the usual length here" —
     * and it means that from the moment it lands, not only once the finger lifts, which is why the
     * preview, the pill and the event that gets created all read it from here.
     */
    fun span(defaultMinutes: Int): IntRangeLike {
        val low = minOf(startMinute, endMinute)
        val high = maxOf(startMinute, endMinute)
        return if (high > low) {
            IntRangeLike(low, high)
        } else {
            IntRangeLike(low, (low + defaultMinutes).coerceAtMost(24 * 60))
        }
    }
}

/** Where a tap has parked a new-event block, waiting for the second tap that opens the editor. */
internal data class NewEventPlacement(val date: LocalDate, val startMinute: Int) {
    fun span(defaultMinutes: Int): IntRangeLike =
        IntRangeLike(startMinute, (startMinute + defaultMinutes).coerceAtMost(24 * 60))
}

internal data class IntRangeLike(val first: Int, val second: Int) {
    /** Tall enough to see and to hit, whatever the range says. */
    fun heightAt(hourHeight: Dp): Dp =
        (hourHeight * ((second - first) / 60f)).coerceAtLeast(20.dp)
}

/** "09:10" or "9:10 AM", from minutes past midnight. Midnight tomorrow prints as midnight. */
private fun minuteLabel(minute: Int, formatter: DateTimeFormatter): String =
    LocalTime.MIDNIGHT.plusMinutes((minute % (24 * 60)).toLong()).format(formatter)

/**
 * The block a tap parks on the grid: an hour of nothing, offering to become an event.
 *
 * Outlined rather than filled, and in the accent rather than a calendar colour, because it is not
 * an event yet — it is a proposal, and it has to be tellable at a glance from the blocks around it
 * that are real. The time is in the pill above it, so a week column only has to fit the plus.
 */
@Composable
private fun NewEventPlaceholder(
    label: String,
    compact: Boolean,
    cornerRadius: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(cornerRadius)
    // Grows in over one Motion.DurationShort. Nothing waits on it — the block is already placed and
    // already tappable on the first frame — so this costs no responsiveness; it only stops a
    // rectangle from materialising out of nothing somewhere the finger was not.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.88f,
        animationSpec = tween(Motion.DurationShort),
        label = "placeholderAppear",
    )
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.5.dp, accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 2.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "New event",
            tint = accent,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
        )
        if (!compact) {
            Text(
                label,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class EventDrag(
    val eventId: Long,
    val instanceStartMillis: Long,
    val deltaDays: Int,
    val deltaMinutes: Int,
    /** Whether the finger has let go and the move has been handed on to be written. */
    val committed: Boolean = false,
)

internal fun Int.roundToStep(step: Int): Int {
    val half = step / 2
    return ((this + half) / step) * step
}

internal fun Int.floorToStep(step: Int): Int = Math.floorDiv(this, step) * step

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
    // The same solid fill a timed block gets, from the same helper, so the two cannot drift apart
    // again — which is exactly what had happened: a solid slab up here and a ten-percent wash down
    // there, for two things that are the same kind of object.
    val colors = eventColors(event.color)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.container)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = MaterialTheme.typography.labelSmall.fontSize
                .scaledBy(LocalEventTextScale.current),
            color = colors.content,
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
    nested: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onMove: ((deltaDays: Int, deltaMinutes: Int) -> Unit)? = null,
    onMovePreview: ((deltaDays: Int, deltaMinutes: Int) -> Unit)? = null,
    onMovePreviewEnd: (committed: Boolean) -> Unit = {},
    dayIndex: Int = 0,
    visibleDayCount: Int = 1,
    eventLeftInDay: Dp = 0.dp,
    dayWidth: Dp = 0.dp,
    hourHeight: Dp = 60.dp,
) {
    val colors = eventColors(event.color)
    val textColor = colors.content
    // The same ink, stepped back, so the time reads as secondary without falling off the fill.
    val mutedTextColor = colors.content.copy(alpha = 0.78f)
    // Too short to stack a title and anything else: one line, vertically centred.
    val slim = heightDp < 30.dp
    val start = event.start.atZone(zone)
    val end = event.end.atZone(zone)
    val is24Hour = LocalUse24HourClock.current
    val locale = currentLocale()
    val timeFmt = remember(is24Hour, locale) { timeFormatter(is24Hour, locale) }
    val showTime = !compact && heightDp >= 40.dp
    val textPadding = when {
        slim -> Modifier.fillMaxSize().padding(horizontal = if (compact) 3.dp else 8.dp)
        // Week columns are only ~48dp wide on a phone. Every dp of padding here costs a character,
        // and once a word no longer fits the line the layout breaks it mid-word ("plannin/g").
        compact -> Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)
        else -> Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 5.dp)
    }
    val textScale = LocalEventTextScale.current
    val titleScale = (if (compact) 10.sp else 13.sp).scaledBy(textScale)
    val detailScale = (if (compact) 10.sp else 11.sp).scaledBy(textScale)
    // One line and an ellipsis when the user has turned wrapping off. A week column is narrow
    // enough that a wrapped title is routinely broken mid-word, and some people would rather see
    // the start of the title than all of it in pieces.
    val maxTitleLines = if (!LocalWrapEventTitles.current) 1 else if (compact) 3 else 2
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(colors.container)
            // A block sitting inside another one is the same colour as the thing underneath it, so
            // without an outline the pair reads as a single shape with a caption halfway down.
            .then(
                if (nested) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(cornerRadius),
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (onMove != null) {
                    Modifier.pointerInput(event.id, event.start, eventLeftInDay, dayWidth, hourHeight) {
                        var totalDrag = Offset.Zero
                        var lastDelta = 0 to 0
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
                                lastDelta = 0 to 0
                                onMovePreview?.invoke(0, 0)
                                // The event is under the finger from here on, so this is the last
                                // moment the user can see that the drag took hold.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                                val delta = deltas()
                                // Quarter-hours and whole columns both count as a step; crossing
                                // either is a place the event could actually be dropped.
                                if (delta != lastDelta) {
                                    lastDelta = delta
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.SegmentFrequentTick,
                                    )
                                }
                                onMovePreview?.invoke(delta.first, delta.second)
                            },
                            onDragCancel = {
                                totalDrag = Offset.Zero
                                lastDelta = 0 to 0
                                onMovePreviewEnd(false)
                            },
                            onDragEnd = {
                                val (deltaDays, deltaMinutes) = deltas()
                                totalDrag = Offset.Zero
                                lastDelta = 0 to 0
                                val moved = deltaDays != 0 || deltaMinutes != 0
                                // Handed on *before* the preview is released, so whoever takes it
                                // can hold the block where it was dropped.
                                if (moved) {
                                    onMove(deltaDays, deltaMinutes)
                                    // Only when it landed somewhere new. Dropping an event back
                                    // where it started changed nothing and should not claim to.
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                }
                                onMovePreviewEnd(moved)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        // No stripe: the fill is the colour now, and a stripe of the same colour on top of it is
        // just a seam. It existed to give a 10%-tinted block something to be identified by.
        if (slim) {
            // A quarter-hour slot is about fifteen dp. There is room for one line of the title and
            // nothing else, and trying to fit more is what used to make these overlap the event
            // below them.
            Box(textPadding, contentAlignment = Alignment.CenterStart) {
                Text(
                    event.title,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    fontSize = 10.sp.scaledBy(textScale),
                    // No leading. A ten-minute event is about ten dp of grid, and the two dp a
                    // default line height adds above and below the glyphs is the difference
                    // between a title and a sliced-off title.
                    lineHeight = 10.sp.scaledBy(textScale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // Title only. The block already says when it is — that is what its position and its
            // length on the grid are for — so printing the times inside it is the same fact twice,
            // taking the room the title wanted.
            Column(textPadding) {
                Text(
                    event.title,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    fontSize = titleScale,
                    maxLines = maxTitleLines,
                    overflow = TextOverflow.Ellipsis,
                    // A word wider than a week column used to be cut at whatever letter ran out of
                    // room ("plannin/g"). Hyphenation breaks it at a syllable, with a hyphen. The
                    // line height is the title's own; the inherited body style spaced 10sp lines
                    // 24sp apart, a third of a block's height for each line.
                    style = LocalTextStyle.current.copy(
                        hyphens = Hyphens.Auto,
                        lineBreak = LineBreak.Paragraph,
                        lineHeight = 1.2.em,
                    ),
                )
            }
        }
    }
}

/**
 * One event's box inside a day column, expressed as fractions of that column's width.
 *
 * Fractions rather than a column index because an event nested inside another does not get a
 * column of its own: it keeps its container's right edge and only gives up ground on the left,
 * drawn on top. An index-based layout has no way to say that — it can only halve the column, which
 * is what turned a full-width "Arbeit" into a 24dp ribbon reading "Ar / bei / t" as soon as a
 * 45-minute break was booked inside it.
 */
internal data class PositionedEvent(
    val event: Event,
    val leftFraction: Float,
    val widthFraction: Float,
    /** 0 for an event nothing contains. Deeper events are drawn later, so they land on top. */
    val depth: Int,
    /**
     * The distance to the top of the next block in the same column, where that is less than the
     * block's own height — in other words, the part of the block that is not about to be painted
     * over.
     *
     * An event too short to hold a line of text is given [MinBlockHeight] anyway, which makes it
     * reach past its own end and under whatever starts there. Drawn at its full height its title
     * ends up centred in a box half of which is covered; drawn at this one, the title is centred
     * in what you can actually see.
     */
    val visibleDp: Dp,
    val topDp: Dp,
    val heightDp: Dp,
)

/** How much of its container's width a nested event gives up on the left. */
private const val NestIndent = 0.12f

/**
 * How far below its container's top an event has to start to be nested inside it. A child drawn on
 * top from the container's first line covered the container's title, so one that starts sooner
 * goes beside it instead.
 */
private val NestTitleClearance = 14.dp

internal fun layoutTimed(
    events: List<Event>,
    hourHeight: Dp,
    zone: ZoneId,
): List<PositionedEvent> {
    if (events.isEmpty()) return emptyList()
    // Longest first among events that start together, so a container is always seen before the
    // things inside it and the containment stack below never has to look backwards.
    val sorted = events.sortedWith(compareBy<Event> { it.start }.thenByDescending { it.end })
    val minLeadMillis = (NestTitleClearance / hourHeight * 3_600_000f).toLong()
    val children = nestingOf(sorted, minLeadMillis)
    val out = mutableListOf<PositionedEvent>()
    place(children[sorted.size], sorted, children, 0f, 1f, 0, hourHeight, zone, out)
    // Painter's order: a nested block has to be drawn after the block it sits inside.
    return out.sortedBy { it.depth }
}

/**
 * Indices of the events directly inside each event; roots live at index `sorted.size`.
 *
 * Works as a stack because [sorted] is in start order: once the innermost open container no longer
 * encloses the event we are placing, nothing deeper can either.
 */
private fun nestingOf(sorted: List<Event>, minLeadMillis: Long): List<MutableList<Int>> {
    val children = List(sorted.size + 1) { mutableListOf<Int>() }
    val open = ArrayDeque<Int>()
    for (i in sorted.indices) {
        while (open.isNotEmpty() && !encloses(sorted[open.last()], sorted[i], minLeadMillis)) {
            open.removeLast()
        }
        children[open.lastOrNull() ?: sorted.size].add(i)
        open.addLast(i)
    }
    return children
}

/**
 * Whether [outer] wholly contains [inner] and [inner] starts at least [minLeadMillis] later.
 *
 * Two events on exactly the same slot enclose each other by the loose reading, which would make one
 * of them a child of the other for no reason. They are peers, and peers go side by side. So is one
 * that starts with its container, or so soon after that it would be drawn over the container's
 * title.
 */
private fun encloses(outer: Event, inner: Event, minLeadMillis: Long): Boolean =
    outer.end >= inner.end &&
        inner.start.toEpochMilli() - outer.start.toEpochMilli() >= maxOf(minLeadMillis, 1L)

/**
 * Lay a set of sibling events out across the band `[left, right)` and recurse into what they hold.
 *
 * Siblings that genuinely overlap in time still go side by side — there is no other honest way to
 * show two half-overlapping meetings — but one that ends before the next begins gets its column
 * back, so a day of back-to-back events stays full width.
 */
@Suppress("LongParameterList")
private fun place(
    group: List<Int>,
    sorted: List<Event>,
    children: List<List<Int>>,
    left: Float,
    right: Float,
    depth: Int,
    hourHeight: Dp,
    zone: ZoneId,
    out: MutableList<PositionedEvent>,
) {
    if (group.isEmpty()) return
    val columnEnds = mutableListOf<Long>()
    val columnOf = mutableMapOf<Int, Int>()
    for (i in group) {
        val e = sorted[i]
        val free = columnEnds.indices.firstOrNull { columnEnds[it] <= e.start.toEpochMilli() }
        if (free != null) {
            columnEnds[free] = e.end.toEpochMilli()
            columnOf[i] = free
        } else {
            columnEnds.add(e.end.toEpochMilli())
            columnOf[i] = columnEnds.size - 1
        }
    }
    val slot = (right - left) / columnEnds.size
    val tops = group.associateWith { i ->
        val startZ = sorted[i].start.atZone(zone)
        hourHeight * (startZ.hour + startZ.minute / 60f + startZ.second / 3600f).coerceIn(0f, 24f)
    }
    // Where the next block in the same column begins — the point past which this one is covered.
    val ceilings = mutableMapOf<Int, Dp>()
    val nextTop = mutableMapOf<Int, Dp>()
    for (i in group.reversed()) {
        val column = columnOf.getValue(i)
        ceilings[column]?.let { nextTop[i] = it }
        ceilings[column] = tops.getValue(i)
    }
    for (i in group) {
        val e = sorted[i]
        val blockLeft = left + slot * columnOf.getValue(i)
        val blockRight = blockLeft + slot
        val startZ = e.start.atZone(zone)
        val endZ = e.end.atZone(zone)
        val startFrac = (startZ.hour + startZ.minute / 60f + startZ.second / 3600f)
            .coerceIn(0f, 24f)
        // Keep a minimum visible slice, but never let the lower bound exceed 24h — an event
        // starting after 23:45 would otherwise make coerceIn's range empty and crash.
        val endFrac = (endZ.hour + endZ.minute / 60f + endZ.second / 3600f)
            .coerceIn((startFrac + 0.25f).coerceAtMost(24f), 24f)
        // Enough for one line of small text and no more. It used to be 32dp, which is over half an
        // hour of grid: a quarter-hour event was inflated to twice its length and drawn straight
        // over whatever started when it ended.
        val top = tops.getValue(i)
        val height = (hourHeight * (endFrac - startFrac)).coerceAtLeast(MinBlockHeight)
        val visible = nextTop[i]?.let { (it - top).coerceAtLeast(0.dp) } ?: height
        out.add(
            PositionedEvent(
                event = e,
                leftFraction = blockLeft,
                widthFraction = blockRight - blockLeft,
                depth = depth,
                visibleDp = minOf(visible, height),
                topDp = top,
                heightDp = height,
            ),
        )
        place(
            group = children[i],
            sorted = sorted,
            children = children,
            left = blockLeft + (blockRight - blockLeft) * NestIndent,
            right = blockRight,
            depth = depth + 1,
            hourHeight = hourHeight,
            zone = zone,
            out = out,
        )
    }
}
