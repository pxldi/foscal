package app.foscal.ui.util

import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.model.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Ids of the calendars the views should draw, re-emitting whenever the provider changes.
 *
 * The provider is the only source: [Calendar.isShown] reads `SYNC_EVENTS` and `VISIBLE`, which
 * every calendar app on the phone shares. Foscal once kept its own tick in DataStore as well, so
 * a calendar unticked elsewhere drew ticked here and a tick here changed nothing elsewhere. Every
 * event-loading view model needs the same answer, so it lives here rather than being restated in
 * each one.
 */
fun visibleCalendarIds(repository: CalendarRepository): Flow<Set<Long>> =
    repository.observeCalendars().map { all ->
        all.asSequence().filter { it.isShown }.map { it.id }.toSet()
    }

/**
 * [visibleCalendarIds] minus the ones taken out of the month grid.
 *
 * Layered rather than folded in, so a calendar switched off everywhere stays off: the month
 * setting can only ever remove, never bring one back.
 */
fun monthCalendarIds(
    repository: CalendarRepository,
    prefs: Preferences,
): Flow<Set<Long>> = combine(
    visibleCalendarIds(repository),
    prefs.monthHiddenCalendarIds,
) { visible, hiddenInMonth ->
    visible - hiddenInMonth.mapNotNull(String::toLongOrNull).toSet()
}

/**
 * Moves the calendars unticked in Foscal's own preferences into `Calendars.VISIBLE`.
 *
 * Every id still present and ticked in the provider is unticked there, once, and then the set is
 * emptied. A refused write is not retried: the calendar then shows ticked, which the user can see
 * and change, while a retry on a later sync could untick a calendar the user has ticked since.
 * When no calendar can be read at all, the set is left alone: that is a missing permission or a
 * wedged provider, and emptying it then would put back every calendar the user had hidden.
 */
suspend fun migrateLegacyHiddenCalendars(repository: CalendarRepository, prefs: Preferences) {
    val legacy = prefs.legacyHiddenCalendarIds.first()
    if (legacy.isEmpty()) return
    val calendars = repository.getCalendars().associateBy { it.id }
    if (calendars.isEmpty()) return
    legacy.mapNotNull { it.toLongOrNull()?.let(calendars::get) }
        .filter { it.visible }
        .forEach { repository.setCalendarVisible(it.id, false) }
    prefs.setLegacyHiddenCalendars(emptySet())
}
