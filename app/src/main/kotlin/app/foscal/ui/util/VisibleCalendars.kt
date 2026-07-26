package app.foscal.ui.util

import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Ids of the calendars the views should draw, re-emitting whenever either input changes.
 *
 * Two independent switches hide a calendar and both must be honoured: `Calendars.VISIBLE` in the
 * provider (owned by the sync adapter and other calendar apps) and the user's own per-calendar
 * toggle in Settings. Every event-loading view model needs the same intersection, so it lives here
 * rather than being restated in each one — the four copies had already begun to drift.
 */
fun visibleCalendarIds(
    repository: CalendarRepository,
    prefs: Preferences,
): Flow<Set<Long>> = combine(
    repository.observeCalendars(),
    prefs.hiddenCalendarIds,
) { all, hidden ->
    all.asSequence()
        .filter { it.visible && it.id.toString() !in hidden }
        .map { it.id }
        .toSet()
}
