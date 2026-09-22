package app.foscal.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            // The three work names can run at once, and two passes interleaved used to lose each
            // other's registry writes, orphaning alarms that then fired for deleted events. The
            // lock covers the read as well as the arming, so the pass that arms last is also the
            // one that read last.
            syncLock.withLock { sync() }
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
            //
            // Only the observer's own run replaces the observer. Any other run uses KEEP: a
            // REPLACE from there cancelled an observer run that had just been triggered, and the
            // calendar change it was about to read went unnoticed. KEEP still re-creates an
            // observer that has finished or been cancelled.
            val policy = if (ReminderSyncScheduler.TAG_OBSERVE in tags) {
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
            syncScheduler.observeCalendarChanges(policy)
        }
        return result
    }

    private suspend fun sync(): Result {
        permission.refresh()
        if (!permission.isGranted) {
            // Nothing to do, and nothing to undo: leaving the existing alarms armed is right, since
            // the user may re-grant at any time and those events have not gone anywhere.
            status.record(ReminderSyncStatus.Outcome.NoPermission, armed = null)
            return Result.success()
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        // A failed read at either step means retry, never reschedule: an empty list would cancel
        // every armed alarm, and a horizon shrunk by an unread offset would cancel every reminder
        // beyond it. Either way the user silently stops being reminded.
        val largestOffset = repository.getLargestReminderOffsetMinutes()
            ?: return readFailed()
        val horizonEnd = ReminderTrigger.horizonEnd(
            now = now,
            zone = zone,
            largestOffsetMinutes = largestOffset,
        )

        val hidden = preferences.hiddenCalendarIds.first().mapNotNull(String::toLongOrNull).toSet()
        val reminders = repository.getUpcomingReminders(now, horizonEnd, zone, hidden)
            ?: return readFailed()

        val armed = scheduler.reschedule(reminders)
        status.record(ReminderSyncStatus.Outcome.Success, armed = armed)
        widgetRefresher.refresh()
        return Result.success()
    }

    private suspend fun readFailed(): Result {
        // armed = null: the alarms from the last pass are still set, so the count stays theirs.
        status.record(ReminderSyncStatus.Outcome.ReadFailed, armed = null)
        return Result.retry()
    }

    private companion object {
        val syncLock = Mutex()
    }
}
