package app.foscal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi
import app.foscal.notifications.ReminderAlarmReceiver
import app.foscal.notifications.ReminderSync
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FoscalApp : Application() {

    @Inject
    lateinit var reminderSync: ReminderSync

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        reminderSync.start()
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
