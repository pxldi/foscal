package app.foscal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.foscal.notifications.ReminderAlarmReceiver
import app.foscal.notifications.ReminderSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FoscalApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var reminderSyncScheduler: ReminderSyncScheduler

    /**
     * Supplies Hilt's worker factory so [app.foscal.notifications.ReminderSyncWorker] can take
     * constructor dependencies. The default WorkManager initializer is removed in the manifest so
     * this is the configuration that actually gets used.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        // Idempotent: re-establishes the periodic backstop and the content-change trigger if either
        // was lost (fresh install, force-stop, "clear data"), and does nothing when both are live.
        reminderSyncScheduler.ensureScheduled()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val name = "Event reminders"
        val desc = "Notifications for upcoming calendar events"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(ReminderAlarmReceiver.CHANNEL_ID, name, importance)
        channel.description = desc
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
