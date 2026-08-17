package app.foscal.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmReminderScheduler.ACTION_FIRE) return

        val eventId = intent.getLongExtra(AlarmReminderScheduler.EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(AlarmReminderScheduler.EXTRA_TITLE) ?: "Event"
        val whenMillis = intent.getLongExtra(AlarmReminderScheduler.EXTRA_WHEN_MILLIS, 0L)
        val location = intent.getStringExtra(AlarmReminderScheduler.EXTRA_LOCATION) ?: ""
        val minutes = intent.getIntExtra(AlarmReminderScheduler.EXTRA_MINUTES, 0)
        val allDay = intent.getBooleanExtra(AlarmReminderScheduler.EXTRA_ALL_DAY, false)

        if (eventId <= 0L) return

        // A fired alarm is consumed, so this is the natural moment to top the schedule back up: it
        // frees a slot under the per-app alarm cap and walks the horizon forward. Without it a
        // device that is never opened drains its armed set one reminder at a time.
        EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java)
            .syncScheduler()
            .syncNow()

        // The provider read and the DataStore read are both disk I/O; onReceive runs on the main
        // thread, so hand off rather than blocking it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notify(context, eventId, title, whenMillis, location, minutes, allDay)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun notify(
        context: Context,
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

        val absolute = whenMillis.takeIf { it > 0L }?.let {
            // All-day events are stored at UTC midnight, so they must be read back in UTC and shown
            // without a time. Rendering one in the device zone printed "Tue, Aug 18 · 02:00" for a
            // holiday that has no clock time at all — and a day earlier than that west of UTC.
            if (allDay) {
                val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(date)
            } else {
                val zdt = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                val pattern =
                    if (use24HourClock(context)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
                DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(zdt)
            }
        }
        val contentText = listOfNotNull(
            leadLabel(displayStart, System.currentTimeMillis()),
            absolute,
            location.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        // Every occurrence of a recurring series shares an event id, so the notification id and the
        // tap intent's request code must include the occurrence start or two upcoming occurrences
        // collapse into one notification pointing at a single instance.
        val key = AlarmReminderScheduler.alarmKey(eventId, whenMillis, minutes)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
            putExtra(MainActivity.EXTRA_OPEN_INSTANCE_START, whenMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context, key, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_calendar)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(tapPi)
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
    }
}
