package app.foscal.ui.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Dates {

    /**
     * Narrow weekday initials in display order, starting at [firstDayOfWeek].
     *
     * [locale] is a parameter rather than a `Locale.getDefault()` read so the labels change with
     * the user's language: the default is captured once per process, which left the weekday strip
     * showing the old language until the app was killed.
     */
    fun weekStartLabels(
        locale: Locale,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): List<String> = (0..6).map {
        firstDayOfWeek.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale)
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
