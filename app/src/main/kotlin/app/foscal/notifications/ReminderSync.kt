package app.foscal.notifications

import app.foscal.core.data.CalendarRepository
import app.foscal.ui.util.Dates
import app.foscal.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
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

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (job != null) return
        job = scope.launch {
            // Observing calendars re-emits whenever the set of calendars changes *and* the
            // instant calendar permission is granted, so reminders self-schedule on first grant
            // and whenever a sync adapter adds a calendar — no app restart required.
            //
            // The date ticker is the second trigger. Provider changes alone would leave a
            // long-lived process scheduling against the horizon it computed at startup, so a device
            // left running with a quiet calendar would stop arming alarms once the original 30-day
            // window ran out. Re-syncing at each midnight walks the horizon forward with it.
            combine(
                repository.observeCalendars(),
                Dates.todayFlow(),
            ) { calendars, _ -> calendars.map { it.id }.toSet() }
                .flatMapLatest { ids ->
                    // The emissions are used only as a change signal; `sync()` re-reads the
                    // reminders itself against a freshly computed horizon.
                    repository.observeEvents(ids, Instant.now(), horizonEnd())
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

    private fun horizonEnd(): Instant =
        LocalDate.now().plusDays(HORIZON_DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant()

    companion object {
        private const val HORIZON_DAYS = 30L
    }
}
