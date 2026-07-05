package app.calendarium.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import app.calendarium.core.model.ScheduledReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface ReminderScheduler {
    fun reschedule(reminders: List<ScheduledReminder>)
}

@Singleton
class AlarmReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private val receiverClass = ReminderAlarmReceiver::class.java

    override fun reschedule(reminders: List<ScheduledReminder>) {
        val am = alarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) return

        val eventIds = reminders.map { it.eventId }.toSet()
        for (eventId in eventIds) {
            for (minutes in CANCEL_PRESETS) {
                pendingIntent(eventId, minutes, createIfMissing = false)?.let { am.cancel(it) }
            }
        }

        for (reminder in reminders) {
            val triggerAt = reminder.triggerAtMillis
            if (triggerAt <= System.currentTimeMillis()) continue
            val pi = pendingIntent(
                eventId = reminder.eventId,
                minutesBefore = reminder.minutesBefore,
                title = reminder.title,
                whenMillis = reminder.startMillis,
                location = reminder.location,
                createIfMissing = true,
            ) ?: continue
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
        }
    }

    private fun pendingIntent(
        eventId: Long,
        minutesBefore: Int,
        title: String? = null,
        whenMillis: Long = 0L,
        location: String? = null,
        createIfMissing: Boolean,
    ): PendingIntent? {
        val intent = Intent().apply {
            action = ACTION_FIRE
            component = ComponentName(
                context.applicationContext.packageName,
                receiverClass.name,
            )
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_MINUTES, minutesBefore)
            if (title != null) {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_WHEN_MILLIS, whenMillis)
                putExtra(EXTRA_LOCATION, location ?: "")
            }
        }
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val finalFlags = if (createIfMissing) flags else flags or PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode(eventId, minutesBefore),
            intent,
            finalFlags,
        )
    }

    private fun requestCode(eventId: Long, minutesBefore: Int): Int {
        val high = (eventId ushr 32).toInt()
        val low = eventId.toInt()
        return low xor (high * 31) xor (minutesBefore * 0x10000)
    }

    companion object {
        const val ACTION_FIRE = "app.calendarium.reminder.FIRE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHEN_MILLIS = "when_millis"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_MINUTES = "minutes"

        private val CANCEL_PRESETS = intArrayOf(
            0, 1, 5, 10, 15, 30, 60, 120, 180, 240, 720, 1440, 2880, 10080,
        )
    }
}
