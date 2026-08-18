package app.foscal.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.DayTapAction
import app.foscal.ui.agenda.AgendaRoute
import app.foscal.ui.agenda.AgendaViewModel
import app.foscal.ui.calendars.CalendarsViewModel
import app.foscal.ui.common.TodayPill
import app.foscal.ui.month.MonthRoute
import app.foscal.ui.month.MonthViewModel
import app.foscal.ui.settings.SettingsScreen
import app.foscal.ui.week.TimelineRoute
import app.foscal.ui.week.WeekViewModel
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * The one screen this app really has, plus the controls that change what it shows.
 *
 * There is no bottom *navigation* bar because there is nowhere to navigate to: Day, 3 Days, Week,
 * Month and Agenda are five lenses on the same events, not five destinations. What sits at the
 * bottom instead is a bottom *app* bar — the switcher, Today, Search and one `+` — because those
 * are the controls used constantly, and the bottom of the screen is the only part of a phone that
 * is comfortably reachable. Views and calendars live in a sheet behind the switcher, which is the
 * only way to keep them one tap away without spending permanent chrome on them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onOpenEditor: (calendarId: Long?, startMillis: Long?, endMillis: Long?) -> Unit,
    onOpenEventDetail: (eventId: Long, instanceStartMillis: Long) -> Unit,
    onOpenSearch: () -> Unit,
) {
    // Null until the stored preference has been read. Rendering Month first and then snapping to
    // the real start view would flash the wrong screen on every cold start.
    var view by rememberSaveable { mutableStateOf<CalendarView?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // Hoisted so the bottom bar can drive them: the Today button belongs to the bar now, but the
    // state it moves lives in whichever view is on screen.
    val monthViewModel: MonthViewModel = hiltViewModel()
    val timelineViewModel: WeekViewModel = hiltViewModel()
    val agendaViewModel: AgendaViewModel = hiltViewModel()
    val calendarsViewModel: CalendarsViewModel = hiltViewModel()
    val settings: BehaviourViewModel = hiltViewModel()
    val prefs by settings.state.collectAsStateWithLifecycle()

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, viewModel = calendarsViewModel)
        return
    }

    LaunchedEffect(prefs.resolvedStartView) {
        if (view == null) view = prefs.resolvedStartView
    }
    val current = view ?: return
    LaunchedEffect(current) { settings.rememberView(current.name) }

    val monthState by monthViewModel.state.collectAsStateWithLifecycle()
    val timelineState by timelineViewModel.state.collectAsStateWithLifecycle()
    val agendaState by agendaViewModel.state.collectAsStateWithLifecycle()
    val calendarsState by calendarsViewModel.state.collectAsStateWithLifecycle()

    val agendaListState = rememberLazyListState()
    var agendaHeaderHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Whichever day the current view is built around. Handing it to the next view is what stops a
    // switch from silently teleporting you back to today.
    val focusedDate = when (current) {
        CalendarView.Month -> monthState.selectedDate ?: monthState.today
        CalendarView.Agenda -> agendaState.today
        else -> {
            val visible = timelineState.days.map { it.date }
            // The middle of the range, not its first day. A week can begin in the month before the
            // one it mostly covers — Aug 31 to Sep 6 is six days of September — and handing the
            // first day to the month view would drop you back into August, undoing the very
            // navigation that got you there. Today still wins when it is on screen, since that is
            // unambiguously the day being looked at.
            visible.firstOrNull { it == timelineState.today }
                ?: visible.getOrNull(visible.size / 2)
                ?: timelineState.anchor
        }
    }

    if (sheetOpen) {
        ViewSheet(
            current = current,
            calendars = calendarsState.items,
            onSelect = { target ->
                val span = target.timelineDays
                when {
                    target == CalendarView.Month -> monthViewModel.goToDate(focusedDate)
                    span != null -> timelineViewModel.showFrom(focusedDate, span)
                    else -> Unit // The agenda is one continuous list; it has nowhere to jump to.
                }
                view = target
                sheetOpen = false
            },
            onToggleCalendar = calendarsViewModel::toggleHidden,
            onOpenSettings = {
                sheetOpen = false
                showSettings = true
            },
            onDismiss = { sheetOpen = false },
        )
    }

    val onEventClick: (Long, Long) -> Unit = { id, instanceStart ->
        onOpenEventDetail(id, instanceStart)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                actions = {
                    Spacer(Modifier.width(8.dp))
                    ViewSwitcher(view = current, onClick = { sheetOpen = true })
                    Spacer(Modifier.width(4.dp))
                    TodayPill(
                        onClick = {
                            when (current) {
                                CalendarView.Month -> monthViewModel.goToMonth(YearMonth.now())
                                // The agenda has no page to jump to, so Today means moving the
                                // list to it. Snapped, not animated: `animateScrollToItem` cannot
                                // know how tall the rows between here and there are, so over a
                                // long distance it travels a guessed amount, discovers where the
                                // target actually is, and animates again — two visible movements
                                // for one tap. Every other view's Today lands in one step; this
                                // one now does too.
                                CalendarView.Agenda -> scope.launch {
                                    val index = agendaState.todayIndex
                                    if (index >= 0) {
                                        agendaListState.scrollToItem(index, -agendaHeaderHeight)
                                    }
                                }
                                else -> timelineViewModel.goToToday()
                            }
                        },
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, "Search")
                    }
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = {
                            // In Month view the `+` means "add to the day I have selected"; there
                            // is no other way to say which day, and a generic new event would
                            // ignore the selection the user just made.
                            val date = monthState.selectedDate.takeIf { current == CalendarView.Month }
                            if (date == null) {
                                onOpenEditor(null, null, null)
                            } else {
                                val start = date.atStartOfDay(ZoneId.systemDefault()).plusHours(9)
                                onOpenEditor(
                                    null,
                                    start.toInstant().toEpochMilli(),
                                    start.plusMinutes(prefs.defaultEventMinutes.toLong())
                                        .toInstant().toEpochMilli(),
                                )
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New event")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    fadeIn(tween(app.foscal.core.ui.theme.Motion.DurationShort)) togetherWith
                        fadeOut(tween(app.foscal.core.ui.theme.Motion.DurationShort))
                },
                label = "calendarView",
            ) { current ->
                when (current) {
                    CalendarView.Month -> MonthRoute(
                        onEventClick = onEventClick,
                        viewModel = monthViewModel,
                    )
                    CalendarView.Agenda -> AgendaRoute(
                        onEventClick = onEventClick,
                        listState = agendaListState,
                        headerHeightPx = agendaHeaderHeight,
                        onHeaderHeight = { agendaHeaderHeight = it },
                        viewModel = agendaViewModel,
                    )
                    else -> TimelineRoute(
                        span = current.timelineDays ?: 7,
                        onEventClick = onEventClick,
                        onNewEvent = { start, end -> onOpenEditor(null, start, end) },
                        // Tapping a day header in Week or 3 Days zooms into that day, which is the
                        // one thing a column header can usefully mean.
                        onOpenDay = { date ->
                            when (prefs.dayTapAction) {
                                DayTapAction.OPEN_DAY -> {
                                    timelineViewModel.showFrom(date, 1)
                                    view = CalendarView.Day
                                }
                                DayTapAction.NEW_EVENT -> {
                                    val start = date.atStartOfDay(ZoneId.systemDefault())
                                        .plusHours(9)
                                    onOpenEditor(
                                        null,
                                        start.toInstant().toEpochMilli(),
                                        start.plusMinutes(prefs.defaultEventMinutes.toLong())
                                            .toInstant().toEpochMilli(),
                                    )
                                }
                            }
                        },
                        viewModel = timelineViewModel,
                    )
                }
            }
        }
    }
}

/**
 * The button that opens the sheet, wearing the view it is currently on.
 *
 * A hamburger tells you nothing about where you are. This shows the layout you are looking at, so
 * the control doubles as a status readout and you rarely have to open it just to check.
 */
@Composable
private fun ViewSwitcher(view: CalendarView, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ViewGlyph(
            view = view,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            view.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}
