package app.foscal.ui.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Dates {

    val weekHeaderFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())

    val fullWeekdayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    val dayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d", Locale.getDefault())

    val monthYearFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    val agendaDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    fun weekStartLabels(firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<String> {
        val order = (0..6).map { firstDayOfWeek.plus(it.toLong()) }
        return order.map { it.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }
    }

    fun startOfWeek(date: LocalDate, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
        val diff = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /**
     * Always returns 42 cells (6 weeks × 7 days) so the grid height stays stable
     * across months. Leading/trailing days are filled with the adjacent month's
     * dates so the user sees continuity — those days should be rendered as
     * "out of month" (greyed) by the caller.
     */
    fun monthCells(month: YearMonth, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate> {
        val firstOfMonth = month.atDay(1)
        val offset = (firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val gridOrigin = firstOfMonth.minusDays(offset.toLong())
        return (0 until 42).map { i -> gridOrigin.plusDays(i.toLong()) }
    }

    fun instantToLocal(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(instant, zone)

    fun todayFlow(zone: ZoneId = ZoneId.systemDefault()): Flow<LocalDate> = callbackFlow {
        var last = LocalDate.now(zone)
        trySend(last)

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "FoscalDateTicker").apply { isDaemon = true }
        }

        lateinit var scheduleNext: () -> Unit
        scheduleNext = {
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).plusSeconds(1)
            val delayMillis = Duration.between(now, nextMidnight).toMillis()
            executor.schedule(
                {
                    val current = LocalDate.now(zone)
                    if (current != last) {
                        last = current
                        trySend(current)
                    }
                    scheduleNext()
                },
                delayMillis.coerceAtLeast(60_000L),
                TimeUnit.MILLISECONDS,
            )
        }
        scheduleNext()

        awaitClose { executor.shutdownNow() }
    }
}
