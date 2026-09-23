package app.foscal.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.foscal.MainActivity
import app.foscal.R
import app.foscal.core.data.UserPreferencesRepository
import app.foscal.core.model.ReminderTrigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How long the notification's Snooze button pushes a reminder back. */
private const val SnoozeMinutes = 10

/** The user tapped Snooze. */
private const val ACTION_SNOOZE = "app.foscal.reminder.SNOOZE"

/**
 * A snoozed reminder coming back.
 *
 * Distinct from [AlarmReminderScheduler.ACTION_FIRE] so the two never share a `PendingIntent`:
 * the scheduler cancels its own alarms by request code, and a snooze must survive that.
 */
private const val ACTION_SNOOZED_FIRE = "app.foscal.reminder.SNOOZED_FIRE"

private val HandledActions = setOf(
    AlarmReminderScheduler.ACTION_FIRE,
    ACTION_SNOOZE,
    ACTION_SNOOZED_FIRE,
)

class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HandledActions) return

        val eventId = intent.getLongExtra(AlarmReminderScheduler.EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Event"
        val whenMillis = intent.getLongExtra(AlarmReminderScheduler.EXTRA_WHEN_MILLIS, 0L)
        val location = intent.getStringExtra(AlarmReminderScheduler.EXTRA_LOCATION) ?: ""
        val minutes = intent.getIntExtra(AlarmReminderScheduler.EXTRA_MINUTES, 0)
        val allDay = intent.getBooleanExtra(AlarmReminderScheduler.EXTRA_ALL_DAY, false)

        if (eventId <= 0L) return

        val key = AlarmReminderScheduler.alarmKey(eventId, whenMillis, minutes)

        if (action == ACTION_SNOOZE) {
            // Take the notification down first: the user has answered it, and leaving it up until
            // the alarm is armed reads as a tap that did nothing.
            NotificationManagerCompat.from(context).cancel(key)
            scheduleSnooze(context, intent, key)
            return
        }

        // A fired alarm is consumed, so this is the natural moment to top the schedule back up: it
        // frees a slot under the per-app alarm cap and walks the horizon forward. Without it a
        // device that is never opened drains its armed set one reminder at a time. A snoozed
        // re-fire is not one of those slots, so it does not count.
        if (action == AlarmReminderScheduler.ACTION_FIRE) {
            EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java)
                .syncScheduler()
                .syncNow()
        }

        // The provider read and the DataStore read are both disk I/O; onReceive runs on the main
        // thread, so hand off rather than blocking it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notify(context, key, eventId, title, whenMillis, location, minutes, allDay)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * @param key the notification id, which is also the request code of everything it launches.
     *   Every occurrence of a recurring series shares an event id, so it has to fold the occurrence
     *   start in or two upcoming occurrences collapse into one notification pointing at one of them.
     */
    private suspend fun notify(
        context: Context,
        key: Int,
        eventId: Long,
        title: String,
        whenMillis: Long,
        location: String,
        minutes: Int,
        allDay: Boolean,
    ) {
        if (!occurrenceExists(context, eventId, whenMillis)) return

        // When the event begins as far as the *user* is concerned. Identical to whenMillis for a
        // timed event; local midnight for an all-day one, whose stored value is UTC midnight. Used
        // for everything the user reads — never for the alarm key or the Instances lookup, which
        // must keep matching the provider's raw value.
        val displayStart = ReminderTrigger.triggerAtMillis(
            startMillis = whenMillis,
            allDay = allDay,
            minutesBefore = 0,
            zone = ZoneId.systemDefault(),
        )

        val contentText = listOfNotNull(
            reminderWhen(
                startMillis = displayStart,
                nowMillis = System.currentTimeMillis(),
                allDay = allDay,
                use24Hour = use24HourClock(context),
                zone = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
            ),
            location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")


        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
            putExtra(MainActivity.EXTRA_OPEN_INSTANCE_START, whenMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context, key, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_calendar)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(tapPi)
            .addAction(
                R.drawable.ic_notification_snooze,
                "Snooze ${SnoozeMinutes}m",
                snoozePendingIntent(context, key, eventId, title, whenMillis, location, minutes, allDay),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setWhen(displayStart.takeIf { whenMillis > 0L } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(key, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    /**
     * Arms a one-shot alarm for [SnoozeMinutes] from now, carrying the reminder through unchanged.
     *
     * Its own action rather than the scheduled one, because `PendingIntent` identity includes the
     * action: a snooze can therefore share a request code with the scheduled alarm it came from
     * without the next sync cancelling it out from under the user.
     */
    private fun scheduleSnooze(context: Context, source: Intent, key: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context.applicationContext,
            key,
            snoozedFireIntent(context, source),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + SnoozeMinutes * 60_000L
        // Exact, and allowed through Doze, for the same reason the original alarm is: a reminder
        // that arrives whenever the phone next happens to wake is not a reminder.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            // The exact-alarm permission was revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun snoozePendingIntent(
        context: Context,
        key: Int,
        eventId: Long,
        title: String,
        whenMillis: Long,
        location: String,
        minutes: Int,
        allDay: Boolean,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(AlarmReminderScheduler.EXTRA_EVENT_ID, eventId)
            putExtra(AlarmReminderScheduler.EXTRA_TITLE, title)
            putExtra(AlarmReminderScheduler.EXTRA_WHEN_MILLIS, whenMillis)
            putExtra(AlarmReminderScheduler.EXTRA_LOCATION, location)
            putExtra(AlarmReminderScheduler.EXTRA_MINUTES, minutes)
            putExtra(AlarmReminderScheduler.EXTRA_ALL_DAY, allDay)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            key,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Whether the specific occurrence this alarm was scheduled for is still on the calendar.
     *
     * Checking only that the event row survives is not enough for a recurring series: deleting or
     * moving one occurrence leaves the master untouched, so the stale alarm still notified about a
     * meeting that no longer happens at that time. The Instances table is the expansion the rest of
     * the app reads, so it is also the one that knows an occurrence was cancelled. Any failure to
     * ask (permission revoked, provider error) falls back to notifying — a spurious reminder is a
     * far smaller harm than a silently dropped one.
     */
    private fun occurrenceExists(context: Context, eventId: Long, startMillis: Long): Boolean {
        if (startMillis <= 0L) return eventExists(context, eventId)
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, startMillis + 1)
        return try {
            context.contentResolver.query(
                builder.build(),
                arrayOf(Instances.BEGIN),
                "${Instances.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { c ->
                var found = false
                while (!found && c.moveToNext()) found = c.getLong(0) == startMillis
                found
            } ?: true
        } catch (_: SecurityException) {
            true
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    private fun eventExists(context: Context, eventId: Long): Boolean = try {
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { it.moveToFirst() } == true
    } catch (_: SecurityException) {
        // Calendar permission revoked; skip the existence check and still notify.
        true
    }

    private suspend fun use24HourClock(context: Context): Boolean =
        EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java)
            .preferences()
            .use24HourClock
            .first()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun preferences(): UserPreferencesRepository
        fun syncScheduler(): ReminderSyncScheduler
    }

    companion object {
        const val CHANNEL_ID = "foscal_reminders"

        /**
         * The broadcast a snooze re-arms: the reminder's extras unchanged, under its own action.
         *
         * The component is named by class literal. `javaClass` inside `Intent(source).apply { }`
         * is the Intent's class, which aimed the alarm at `android.content.Intent` and lost it.
         */
        internal fun snoozedFireIntent(context: Context, source: Intent): Intent =
            Intent(source).apply {
                action = ACTION_SNOOZED_FIRE
                component = ComponentName(context, ReminderAlarmReceiver::class.java)
            }
    }
}
