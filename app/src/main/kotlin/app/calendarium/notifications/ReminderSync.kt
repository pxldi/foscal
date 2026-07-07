package app.calendarium.notifications

import app.calendarium.core.data.CalendarRepository
import app.calendarium.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
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
    private val widgetRefresher: WidgetRefresher,
) {

    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (job != null) return
        job = scope.launch {
            // Observing calendars re-emits whenever the set of calendars changes *and* the
            // instant calendar permission is granted, so reminders self-schedule on first grant
            // and whenever a sync adapter adds a calendar — no app restart required.
            repository.observeCalendars()
                .flatMapLatest { calendars ->
                    val ids = calendars.map { it.id }.toSet()
                    repository.observeEvents(ids, horizonStart(), horizonEnd())
                }
                .debounce(2_000L)
                .collect {
                    sync()
                    widgetRefresher.refresh()
                }
        }
    }

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
