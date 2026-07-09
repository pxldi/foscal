package app.foscal.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.foscal.MainActivity
import app.foscal.R
import app.foscal.core.data.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
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

        if (eventId <= 0L) return

        val resolver = context.contentResolver
        val exists = try {
            resolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Events._ID),
                "${android.provider.CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { it.moveToFirst() } == true
        } catch (_: SecurityException) {
            // Calendar permission revoked; skip the existence check and still notify.
            true
        }
        if (!exists) return

        val contentText = buildString {
            if (minutes > 0) append("In ${formatMinutes(minutes)} · ")
            if (whenMillis > 0L) {
                val zdt = Instant.ofEpochMilli(whenMillis).atZone(ZoneId.systemDefault())
                val pattern = if (use24HourClock(context)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
                append(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(zdt))
            }
            if (location.isNotBlank()) append(" · $location")
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
            putExtra(MainActivity.EXTRA_OPEN_INSTANCE_START, whenMillis)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context, eventId.toInt(), tapIntent,
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
            .build()

        try {
            NotificationManagerCompat.from(context).notify(eventId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }

    private fun use24HourClock(context: Context): Boolean = runBlocking {
        EntryPointAccessors.fromApplication(context, ReceiverEntryPoint::class.java)
            .preferences()
            .use24HourClock
            .first()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun preferences(): UserPreferencesRepository
    }

    companion object {
        const val CHANNEL_ID = "foscal_reminders"
    }
}
