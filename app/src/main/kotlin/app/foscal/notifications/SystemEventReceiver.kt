package app.foscal.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Re-arms reminders after any system event that silently invalidates every alarm we hold.
 *
 * `AlarmManager` keeps nothing across a reboot, and an app update wipes our alarms too — on both,
 * a user's reminders simply stop until they next open the app. Clock and timezone changes are more
 * subtle but just as wrong: alarms are armed at absolute instants, so flying from Berlin to New
 * York leaves every reminder firing on Berlin time, and all-day reminders anchored to local
 * midnight are now anchored to the wrong midnight entirely.
 *
 * The work itself is deliberately not done here. A receiver gets ~10 seconds before the system
 * declares it hung, and it runs during the busiest moments a device has (boot, package install), so
 * this only enqueues and returns — WorkManager owns the retry and the timing.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val scheduler = EntryPointAccessors
            .fromApplication(context, SystemEventEntryPoint::class.java)
            .syncScheduler()
        // ensureScheduled re-creates the periodic backstop and the content trigger, both of which a
        // reboot or reinstall may have dropped; syncNow covers the alarms themselves.
        scheduler.ensureScheduled()
        scheduler.syncNow()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SystemEventEntryPoint {
        fun syncScheduler(): ReminderSyncScheduler
    }

    private companion object {
        /**
         * `QUICKBOOT_POWERON` and its HTC-specific spelling are what several OEMs send instead of
         * `BOOT_COMPLETED` when resuming from their fast-boot state. The manifest has always
         * registered for them; the previous receiver then rejected them in code, so on exactly the
         * devices that need the workaround, reminders never came back after a restart.
         */
        val HANDLED_ACTIONS = setOf(
            // LOCKED_BOOT_COMPLETED is deliberately absent: receiving it requires
            // android:directBootAware, and our state lives in credential-encrypted storage that is
            // not readable until the user unlocks anyway.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
