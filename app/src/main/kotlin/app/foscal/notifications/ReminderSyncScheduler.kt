package app.foscal.notifications

import android.content.Context
import android.os.Build
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for "reminders need re-arming".
 *
 * Everything that could invalidate the armed set — a provider change, a reboot, an app update, a
 * timezone change, an alarm firing and thereby freeing a slot — routes here rather than reading the
 * calendar and touching AlarmManager itself. Two things make that worth the indirection:
 *
 * 1. **It survives process death.** The previous design observed the provider from a flow owned by
 *    the Application object. That works only while the process happens to be alive; Android kills
 *    an idle app within minutes, and from then on nothing re-armed anything until the user next
 *    opened the app. WorkManager persists its queue to disk and re-runs across reboots.
 * 2. **It cannot stampede.** Unique work names collapse a burst of provider notifications (a CalDAV
 *    sync writing 300 rows) into one run instead of 300.
 */
@Singleton
class ReminderSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Establishes the two standing schedules. Safe and cheap to call on every app start.
     *
     * The periodic run is a backstop, not the main mechanism: it exists so that a device whose
     * content observer never fires — because the app was force-stopped, or the OEM suspended it —
     * still recovers within a few hours instead of never.
     */
    fun ensureScheduled() {
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            // KEEP, not UPDATE: REPLACE/UPDATE on every app start resets the period, so a user who
            // opens the app more often than the interval would never let a periodic run happen.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderSyncWorker>(
                BACKSTOP_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )
        // KEEP: a healthy observer must survive an app start untouched. REPLACE here would cancel
        // an observer that is *running* — opening the app mid-sync would kill that sync — while
        // KEEP still re-creates one that was cancelled or consumed.
        observeCalendarChanges(ExistingWorkPolicy.KEEP)
    }

    /**
     * Arms a one-shot job that waits for the calendar provider to change.
     *
     * Content-URI triggers only exist on one-time work, so the worker re-arms this every time it
     * runs. [Constraints.Builder.setTriggerContentUpdateDelay] does the debouncing the old in-process
     * flow did with `debounce(2s)`, except the system holds the timer, so a batch sync that starts
     * while the app is dead still coalesces.
     *
     * [policy] is [ExistingWorkPolicy.REPLACE] only from the observer's own run, which knows its
     * trigger has been consumed; see the call in [ReminderSyncWorker] for why that call has to be
     * the last thing a run does, and why every other run passes KEEP.
     */
    fun observeCalendarChanges(policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<ReminderSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    // `true` = also fire for descendants, which is what carries Events, Instances
                    // and Reminders changes; the bare authority URI alone rarely notifies.
                    .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(TRIGGER_DELAY_SECONDS, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            .addTag(TAG_OBSERVE)
            .build()
        workManager.enqueueUniqueWork(WORK_OBSERVE, policy, request)
    }

    /**
     * Runs a sync as soon as the system allows, for the moments where waiting on a content trigger
     * is wrong: first permission grant, reboot, app update, clock or timezone change.
     */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<ReminderSyncWorker>()
            .apply {
                // Below 31 WorkManager runs expedited work as a foreground service and asks the
                // worker for a notification; CoroutineWorker throws there, so the sync failed
                // before doWork ran. Every caller runs in a live process, so plain work starts at once.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        // APPEND_OR_REPLACE keeps a running sync from being cancelled halfway — cancelling mid-pass
        // would leave the registry describing alarms that were never armed.
        workManager.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_PERIODIC = "foscal_reminder_sync_periodic"
        const val WORK_OBSERVE = "foscal_reminder_sync_observe"
        const val WORK_NOW = "foscal_reminder_sync_now"

        /** Marks the observer's run, the only one allowed to replace the observer. */
        const val TAG_OBSERVE = "foscal_reminder_sync_observer"

        private const val BACKSTOP_INTERVAL_HOURS = 6L
        private const val TRIGGER_DELAY_SECONDS = 10L
        private const val TRIGGER_MAX_DELAY_SECONDS = 120L
    }
}
