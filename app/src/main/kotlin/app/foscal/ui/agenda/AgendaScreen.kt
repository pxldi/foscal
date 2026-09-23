package app.foscal.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Event
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.amberTextColor
import app.foscal.core.ui.theme.onTodayDiscColor
import app.foscal.core.ui.theme.todayDiscColor
import app.foscal.ui.util.Dates
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.rememberDateFormatter
import app.foscal.ui.util.timeFormatter
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

/** Clears the FAB, which floats over the list and would otherwise sit on the last card forever. */
private val AgendaBottomInset = 88.dp

/** Width of the date gutter. Shared by the day rows and the month header so they line up. */
private val AgendaGutterWidth = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaRoute(
    onEventClick: (eventId: Long, instanceStartMillis: Long) -> Unit,
    /**
     * Hoisted because the Today button lives in the bottom bar now, and scrolling this list is the
     * only thing Today can mean here — the agenda has no page to jump to.
     */
    listState: LazyListState,
    /**
     * A sticky header is drawn *over* the list, so scrolling a day to index 0 hides it behind the
     * month header. Offsetting by the header's own measured height puts the day just below it —
     * measured rather than a dp constant so it stays right at any font scale.
     */
    headerHeightPx: Int,
    onHeaderHeight: (Int) -> Unit,
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Saved rather than merely remembered: opening an event takes the agenda out of composition,
    // and a plain `remember` would forget it had already parked and snap the list back to today,
    // throwing away the position `listState` itself restores perfectly well.
    var positionedAtToday by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.items, headerHeightPx) {
        if (!positionedAtToday && state.todayIndex >= 0 && headerHeightPx > 0) {
            positionedAtToday = true
            listState.scrollToItem(state.todayIndex, -headerHeightPx)
        }
    }

    AgendaPaging(listState, state, viewModel::loadOlder, viewModel::loadNewer)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text("Agenda", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            AgendaEmpty(state.hasVisibleCalendars, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(bottom = AgendaBottomInset),
        ) {
            state.items.forEach { item ->
                when (item) {
                    is AgendaItem.MonthHeader -> stickyHeader(key = item.yearMonth) {
                        AgendaMonthHeader(
                            yearMonth = item.yearMonth,
                            today = state.today,
                            modifier = Modifier.onSizeChanged { onHeaderHeight(it.height) },
                        )
                    }

                    // No `animateItem`: this list grows by prepending and appending whole pages,
                    // and a placement animation on every existing row turns each page arriving
                    // into a second, unasked-for movement of the whole list.
                    is AgendaItem.Day -> item(key = item.day.date) {
                        AgendaDayRow(
                            day = item.day,
                            today = state.today,
                            onEventClick = onEventClick,
                        )
                    }
                }
            }
        }
    }
}

/** How many rows from either end the next page starts loading. */
private const val AgendaPrefetchRows = 5

/** One sample of "where are we, and how much is loaded" — the input the paging decision needs. */
private data class AgendaEdges(
    val first: Int,
    val last: Int,
    val total: Int,
    val windowStart: LocalDate,
    val windowEnd: LocalDate,
    /** Loaded and still nothing to show, so no list exists to scroll to either end of. */
    val emptyAfterLoad: Boolean,
)

/**
 * Extends the loaded window when either end of the list comes into view.
 *
 * The tricky part is that a page arriving is not guaranteed to add any rows — an agenda skips empty
 * days, so widening the window by two months across a quiet stretch can produce nothing at all. The
 * sample therefore includes the window edges as well as the scroll position, which means a page
 * landing re-runs the decision by itself and the next page is requested immediately. Without that,
 * loading only ever happened in response to a scroll: you would reach the bottom, the page would
 * arrive empty, and nothing would ask again until you scrolled up and back down. Each direction
 * still remembers the window it last asked about, so one page is requested per window rather than
 * one per frame, and the request stops for good once the window hits its cap and stops moving.
 */
@Composable
private fun AgendaPaging(
    listState: LazyListState,
    state: AgendaUiState,
    loadOlder: () -> Unit,
    loadNewer: () -> Unit,
) {
    val current by rememberUpdatedState(state)
    val older by rememberUpdatedState(loadOlder)
    val newer by rememberUpdatedState(loadNewer)

    LaunchedEffect(listState) {
        var requestedStart: LocalDate? = null
        var requestedEnd: LocalDate? = null
        snapshotFlow {
            val info = listState.layoutInfo
            AgendaEdges(
                first = info.visibleItemsInfo.firstOrNull()?.index ?: 0,
                last = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                total = info.totalItemsCount,
                windowStart = current.windowStart,
                windowEnd = current.windowEnd,
                emptyAfterLoad = current.loaded && current.items.isEmpty(),
            )
        }.distinctUntilChanged().collect { edges ->
            // Nothing within the first two months either way used to be the end of the agenda:
            // with no rows there was no edge to scroll to, so nothing asked for the next page.
            if (edges.emptyAfterLoad) {
                if (requestedStart != edges.windowStart) {
                    requestedStart = edges.windowStart
                    older()
                }
                if (requestedEnd != edges.windowEnd) {
                    requestedEnd = edges.windowEnd
                    newer()
                }
                return@collect
            }
            if (edges.total == 0) return@collect
            if (edges.first <= AgendaPrefetchRows && requestedStart != edges.windowStart) {
                requestedStart = edges.windowStart
                older()
            }
            if (edges.last >= edges.total - 1 - AgendaPrefetchRows &&
                requestedEnd != edges.windowEnd
            ) {
                requestedEnd = edges.windowEnd
                newer()
            }
        }
    }
}

@Composable
private fun AgendaEmpty(hasVisibleCalendars: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (hasVisibleCalendars) "No events."
            else "No visible calendars. Open Settings to enable one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The month and year the rows below belong to. Opaque and full-bleed because it is a sticky header:
 * a transparent one lets the scrolling rows show through underneath it.
 */
@Composable
private fun AgendaMonthHeader(
    yearMonth: YearMonth,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val isCurrentMonth = yearMonth == YearMonth.from(today)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = yearMonth.atDay(1).format(rememberDateFormatter("LLLL yyyy")),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = BricolageFamily,
                fontWeight = FontWeight.Bold,
                color = if (isCurrentMonth) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgendaDateGutter(date = day.date, isToday = day.date == today)
        Column(
            modifier = Modifier.weight(1f),
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

/**
 * Day number over weekday initials. Today is a filled circle rather than a lozenge so it reads as
 * the same marker the month grid uses for the current day.
 */
@Composable
private fun AgendaDateGutter(date: LocalDate, isToday: Boolean) {
    Column(
        modifier = Modifier.width(AgendaGutterWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .then(
                    if (isToday) {
                        Modifier
                            .clip(CircleShape)
                            .background(todayDiscColor())
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = BricolageFamily,
                fontWeight = FontWeight.SemiBold,
                color = if (isToday) onTodayDiscColor()
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = date.format(rememberDateFormatter("EEE")),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
            // Amber as text, so the darkened form: the disc colour is 1.5:1 on this surface.
            color = if (isToday) amberTextColor()
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            val subtitle = buildEventSubtitle(event, date, LocalUse24HourClock.current, currentLocale())
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun buildEventSubtitle(
    event: Event,
    date: LocalDate,
    is24Hour: Boolean,
    locale: Locale,
): String {
    val parts = mutableListOf<String>()
    if (event.allDay) {
        parts += "All day"
    } else {
        val zone = ZoneId.systemDefault()
        val fmt = timeFormatter(is24Hour, locale)
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
