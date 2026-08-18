package app.foscal.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The range of days currently loaded from the calendar provider, inclusive at both ends. */
data class LoadedWindow(val from: LocalDate, val to: LocalDate) {

    fun covers(first: LocalDate, last: LocalDate): Boolean =
        !first.isBefore(from) && !last.isAfter(to)

    fun startInstant(zone: ZoneId): Instant = from.atStartOfDay(zone).toInstant()

    /** Exclusive, so the last day is loaded whole. */
    fun endInstant(zone: ZoneId): Instant = to.plusDays(1).atStartOfDay(zone).toInstant()
}

/**
 * Decides how much of the calendar to hold in memory, and — more to the point — when *not* to go
 * and fetch it again.
 *
 * Paging used to derive the query range straight from the visible dates, so every swipe tore down
 * the previous query and issued a new one. A `CalendarContract.Instances` query is not cheap: the
 * provider expands recurrences across the range, across every synced calendar, and the cost grows
 * with how much calendar the person actually has. It landed on the main thread as a fresh event
 * map partway through the page-turn animation, which is a recomposition of both pages at the worst
 * possible moment — and it did so even when the range had already been loaded a swipe earlier.
 *
 * So the window is sticky. It is wide enough to page through for weeks without touching the
 * provider, and it only moves when the visible range gets close enough to an edge that the next
 * few swipes might run off it.
 */
object DayWindow {

    /**
     * How far back to look beyond what is wanted. A multi-day event that began before the window
     * still has to come back from the provider, and the Instances table returns only what overlaps
     * the range it was asked for.
     */
    const val LookBackDays = 31L

    /** How much either side of the visible range is loaded before it is asked for. */
    const val PadDays = 62L

    /** How close the visible range may drift to an edge before the window is moved. */
    const val EdgeDays = 21L

    fun around(first: LocalDate, last: LocalDate): LoadedWindow = LoadedWindow(
        from = first.minusDays(LookBackDays + PadDays),
        to = last.plusDays(PadDays),
    )

    /** [current] if it still has room around [first]..[last], otherwise a fresh window centred there. */
    fun keepOrMove(current: LoadedWindow, first: LocalDate, last: LocalDate): LoadedWindow =
        if (current.covers(first.minusDays(LookBackDays + EdgeDays), last.plusDays(EdgeDays))) {
            current
        } else {
            around(first, last)
        }
}
