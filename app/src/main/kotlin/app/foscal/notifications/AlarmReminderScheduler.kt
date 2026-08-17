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
    /**
     * Replaces every alarm this app has armed with the ones in [reminders], and reports how many
     * ended up armed.
     *
     * Callers must never pass an empty list to mean "I could not read the calendar" — that is an
     * instruction to disarm everything. See [app.foscal.core.data.CalendarRepository.getUpcomingReminders].
     */
    fun reschedule(reminders: List<ScheduledReminder>): Int
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

    override fun reschedule(reminders: List<ScheduledReminder>): Int {
        val am = alarmManager ?: return 0
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

        val ordered = selectAlarms(reminders, System.currentTimeMillis())

        val scheduled = mutableSetOf<String>()
        try {
            for (reminder in ordered) {
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
                // One bad alarm must not abort the rest. Both throws below are reachable in normal
                // use — the exact-alarm permission can be revoked between the capability check and
                // this call, and the per-app alarm cap is enforced here rather than at check time.
                try {
                    if (canExact) {
                        // Deliberately NOT setAlarmClock. That API is for the device's user-facing
                        // alarm clock: it publishes every reminder as the system "next alarm"
                        // (status bar, lock screen, Quick Settings), and its AlarmClockInfo carries
                        // a showIntent that the system launches when the user taps that chip. With
                        // our firing broadcast as the showIntent, tapping it posted the reminder
                        // immediately — a notification claiming an event was minutes away when it
                        // was still weeks out.
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            reminder.triggerAtMillis,
                            pi,
                        )
                    } else {
                        // Exact-alarm permission is not held (rare for a calendar app, but the user
                        // can revoke it): still deliver, just without exact-to-the-minute
                        // guarantees, rather than silently dropping the reminder.
                        am.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            reminder.triggerAtMillis,
                            pi,
                        )
                    }
                    scheduled += requestCode.toString()
                } catch (_: SecurityException) {
                    pi.cancel()
                } catch (_: IllegalStateException) {
                    pi.cancel()
                }
            }
        } finally {
            // Written even if the loop dies unexpectedly. The registry is the *only* record of what
            // is armed, so losing it strands every alarm set in this pass: the next reschedule
            // cannot cancel what it cannot name, and those alarms fire forever.
            registry.edit().putStringSet(KEY_CODES, scheduled).apply()
        }
        return scheduled.size
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
                putExtra(EXTRA_ALL_DAY, extras.allDay)
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
        const val EXTRA_ALL_DAY = "all_day"

        private const val REGISTRY_PREFS = "foscal_scheduled_alarms"
        private const val KEY_CODES = "request_codes"
        private const val KEY_LEGACY_CLEARED = "legacy_cleared"

        /**
         * Headroom under Android 12+'s 500-alarm-per-app ceiling. The budget is shared with any
         * alarm the framework attributes to us, so filling it exactly would make us the app that
         * throws first.
         */
        const val MAX_ALARMS = 450

        private val LEGACY_PRESETS = intArrayOf(
            0, 1, 5, 10, 15, 30, 60, 120, 180, 240, 720, 1440, 2880, 10080,
        )

        /**
         * The reminders to actually arm, soonest first, given the current time.
         *
         * Android 12+ caps an app at 500 concurrent exact alarms and throws once that is exceeded;
         * a shared work calendar with a couple of daily recurring events reaches that inside one
         * horizon, and before this cap the throw aborted the arming loop — so the alarms that got
         * dropped were arbitrary rather than the furthest away. Arming in trigger order means the
         * cap costs only the most distant reminders, which the next sync re-arms long before they
         * are due.
         *
         * Kept pure and separate from [reschedule] so the selection rules can be tested without a
         * live AlarmManager.
         */
        fun selectAlarms(
            reminders: List<ScheduledReminder>,
            nowMillis: Long,
            max: Int = MAX_ALARMS,
        ): List<ScheduledReminder> = reminders
            .asSequence()
            .filter { it.triggerAtMillis > nowMillis }
            .distinctBy { alarmKey(it.eventId, it.startMillis, it.minutesBefore) }
            .sortedBy { it.triggerAtMillis }
            .take(max)
            .toList()

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
