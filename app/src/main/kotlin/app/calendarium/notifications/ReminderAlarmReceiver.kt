package app.calendarium.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.calendarium.MainActivity
import app.calendarium.R
import app.calendarium.notifications.AlarmReminderScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val exists = resolver.query(
            android.provider.CalendarContract.Events.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Events._ID),
            "${android.provider.CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { it.moveToFirst() } == true
        if (!exists) return

        val contentText = buildString {
            if (minutes > 0) append("In ${formatMinutes(minutes)} · ")
            if (whenMillis > 0L) {
                val zdt = Instant.ofEpochMilli(whenMillis).atZone(ZoneId.systemDefault())
                append(DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm").format(zdt))
            }
            if (location.isNotBlank()) append(" · $location")
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_event_id", eventId)
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

    companion object {
        const val CHANNEL_ID = "calendarium_reminders"
    }
}
