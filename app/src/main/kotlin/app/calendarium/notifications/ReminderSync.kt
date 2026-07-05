package app.calendarium.notifications

import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.notifications.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the calendar provider for changes and re-schedules alarm-clock reminders
 * for the next [HORIZON_DAYS] days.
 *
 * Runs an initial sync on creation and debounces subsequent updates to avoid
 * excessive AlarmManager churn (e.g. during batch sync).
 */
@Singleton
class ReminderSync @Inject constructor(
    private val repository: CalendarRepository,
    private val scheduler: ReminderScheduler,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            val zone = ZoneId.systemDefault()
            combine(
                repository.observeCalendars(),
                repository.observeEvents(allCalendarIdsSuspended(), horizonStart(), horizonEnd()),
            ) { _, _ -> Unit }
                .debounce(2_000L)
                .collect { sync() }
        }
    }

    private suspend fun allCalendarIdsSuspended(): Set<Long> =
        repository.getCalendars().map { it.id }.toSet()

    private suspend fun sync() {
        val now = Instant.now()
        val reminders = repository.getUpcomingReminders(now, horizonEnd())
        scheduler.reschedule(reminders)
    }

    private fun horizonStart(): Instant = Instant.now()
    private fun horizonEnd(): Instant =
        LocalDate.now().plusDays(HORIZON_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant()

    companion object {
        private const val HORIZON_DAYS = 30L
    }
}
