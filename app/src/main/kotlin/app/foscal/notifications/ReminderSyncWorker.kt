package app.foscal.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.Preferences
import app.foscal.core.data.ReminderSyncStatus
import app.foscal.core.model.ReminderTrigger
import app.foscal.widget.WidgetRefresher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/**
 * Re-reads the calendar and re-arms every reminder alarm.
 *
 * This is the only place that decides what should be scheduled. Receivers, the app start-up path
 * and the content observer all funnel into it via [ReminderSyncScheduler] so there is exactly one
 * implementation of the horizon and failure rules to get right.
 */
@HiltWorker
class ReminderSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: CalendarRepository,
    private val scheduler: ReminderScheduler,
    private val syncScheduler: ReminderSyncScheduler,
    private val permission: CalendarPermissionState,
    private val preferences: Preferences,
    private val status: ReminderSyncStatus,
    private val widgetRefresher: WidgetRefresher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val result = try {
            sync()
        } finally {
            // Re-arm the single-use content trigger, last and unconditionally.
            //
            // Last, because this replaces a unique work name that *this run may itself be* — when
            // the trigger fires, the job executing is the one being replaced, and WorkManager
            // cancels a running instance to make room for the replacement. Doing it first therefore
            // cancelled the worker mid-flight: the trigger re-armed forever while no alarm was ever
            // re-scheduled, which is a worse failure than the one this class exists to fix. Placed
            // here, every durable side effect has already happened, so a cancelled coroutine costs
            // nothing but the return value.
            //
            // Unconditionally, because the trigger is consumed by firing: one skipped re-arm and
            // the app stops noticing calendar changes until the periodic backstop runs.
            syncScheduler.observeCalendarChanges()
        }
        return result
    }

    private suspend fun sync(): Result {
        permission.refresh()
        if (!permission.isGranted) {
            // Nothing to do, and nothing to undo: leaving the existing alarms armed is right, since
            // the user may re-grant at any time and those events have not gone anywhere.
            status.record(ReminderSyncStatus.Outcome.NoPermission, armed = 0)
            return Result.success()
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val horizonEnd = ReminderTrigger.horizonEnd(
            now = now,
            zone = zone,
            largestOffsetMinutes = repository.getLargestReminderOffsetMinutes(),
        )

        val hidden = preferences.hiddenCalendarIds.first().mapNotNull(String::toLongOrNull).toSet()
        val reminders = repository.getUpcomingReminders(now, horizonEnd, zone, hidden)
        if (reminders == null) {
            // The provider could not be read. Retry rather than reschedule: passing an empty list on
            // would cancel every armed alarm, which is the worst possible response to a transient
            // failure — the user silently stops being reminded about anything.
            status.record(ReminderSyncStatus.Outcome.ReadFailed, armed = 0)
            return Result.retry()
        }

        val armed = scheduler.reschedule(reminders)
        status.record(ReminderSyncStatus.Outcome.Success, armed = armed)
        widgetRefresher.refresh()
        return Result.success()
    }
}
