package app.foscal.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import app.foscal.core.model.ScheduledReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface ReminderScheduler {
    fun reschedule(reminders: List<ScheduledReminder>)
}

@Singleton
class AlarmReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private val receiverClass = ReminderAlarmReceiver::class.java
    private val registry by lazy {
        context.applicationContext.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
    }

    override fun reschedule(reminders: List<ScheduledReminder>) {
        val am = alarmManager ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        // Cancel exactly what we scheduled last time. Deriving the set to cancel from the *new*
        // reminders instead would strand alarms whose event was deleted, whose reminder was
        // removed, or whose occurrence moved — none of those appear in the new list.
        for (code in registry.getStringSet(KEY_CODES, emptySet()).orEmpty()) {
            val requestCode = code.toIntOrNull() ?: continue
            pendingIntent(requestCode, createIfMissing = false)?.let {
                am.cancel(it)
                it.cancel()
            }
        }
        cancelLegacyAlarms(am, reminders)

        val scheduled = mutableSetOf<String>()
        for (reminder in reminders) {
            val triggerAt = reminder.triggerAtMillis
            if (triggerAt <= System.currentTimeMillis()) continue
            val requestCode = alarmKey(
                reminder.eventId,
                reminder.startMillis,
                reminder.minutesBefore,
            )
            val pi = pendingIntent(
                requestCode = requestCode,
                createIfMissing = true,
                extras = reminder,
            ) ?: continue
            if (canExact) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
            } else {
                // Exact-alarm permission is not held (rare for a calendar app, but the user can
                // revoke it): still deliver, just without exact-to-the-minute guarantees, rather
                // than silently dropping the reminder.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            scheduled += requestCode.toString()
        }
        registry.edit().putStringSet(KEY_CODES, scheduled).apply()
    }

    /**
     * Releases alarms scheduled by builds that keyed the request code on `(eventId, minutes)`
     * alone. They survive an app update, so without this pass an upgrading user keeps a stale
     * alarm per event forever.
     */
    private fun cancelLegacyAlarms(am: AlarmManager, reminders: List<ScheduledReminder>) {
        if (registry.getBoolean(KEY_LEGACY_CLEARED, false)) return
        for (eventId in reminders.map { it.eventId }.toSet()) {
            for (minutes in LEGACY_PRESETS) {
                val high = (eventId ushr 32).toInt()
                val low = eventId.toInt()
                val code = low xor (high * 31) xor (minutes * 0x10000)
                pendingIntent(code, createIfMissing = false)?.let {
                    am.cancel(it)
                    it.cancel()
                }
            }
        }
        registry.edit().putBoolean(KEY_LEGACY_CLEARED, true).apply()
    }

    private fun pendingIntent(
        requestCode: Int,
        createIfMissing: Boolean,
        extras: ScheduledReminder? = null,
    ): PendingIntent? {
        // Only the action and component take part in PendingIntent matching (extras do not), so a
        // cancel-only lookup can leave `extras` null and still resolve the live alarm.
        val intent = Intent().apply {
            action = ACTION_FIRE
            component = ComponentName(
                context.applicationContext.packageName,
                receiverClass.name,
            )
            if (extras != null) {
                putExtra(EXTRA_EVENT_ID, extras.eventId)
                putExtra(EXTRA_MINUTES, extras.minutesBefore)
                putExtra(EXTRA_TITLE, extras.title)
                putExtra(EXTRA_WHEN_MILLIS, extras.startMillis)
                putExtra(EXTRA_LOCATION, extras.location ?: "")
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val finalFlags = if (createIfMissing) flags else flags or PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode,
            intent,
            finalFlags,
        )
    }

    companion object {
        const val ACTION_FIRE = "app.foscal.reminder.FIRE"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_WHEN_MILLIS = "when_millis"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_MINUTES = "minutes"

        private const val REGISTRY_PREFS = "foscal_scheduled_alarms"
        private const val KEY_CODES = "request_codes"
        private const val KEY_LEGACY_CLEARED = "legacy_cleared"

        private val LEGACY_PRESETS = intArrayOf(
            0, 1, 5, 10, 15, 30, 60, 120, 180, 240, 720, 1440, 2880, 10080,
        )

        /**
         * Identifies one reminder of one *occurrence*. [startMillis] must be part of the key:
         * every instance of a recurring series shares an event id, so keying on the event alone
         * makes each occurrence's `FLAG_UPDATE_CURRENT` alarm overwrite the previous one and only
         * the last occurrence in the horizon ever fires.
         */
        fun alarmKey(eventId: Long, startMillis: Long, minutesBefore: Int): Int {
            var h = eventId xor (eventId ushr 32)
            h = h * 31 + startMillis
            h = h * 31 + minutesBefore
            return (h xor (h ushr 32)).toInt()
        }
    }
}
