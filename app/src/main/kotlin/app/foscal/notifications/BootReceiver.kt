package app.foscal.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.foscal.core.data.CalendarRepository
import app.foscal.notifications.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val entryPoint = EntryPointAccessors.fromApplication(
            context, BootReceiverEntryPoint::class.java,
        )
        val repository = entryPoint.repository()
        val scheduler = entryPoint.scheduler()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // finish() must run even if the provider read fails: an unfinished BroadcastReceiver
            // result keeps the process alive until the system times it out and kills it.
            try {
                val now = Instant.now()
                val horizon =
                    LocalDate.now().plusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant()
                val reminders = repository.getUpcomingReminders(now, horizon)
                scheduler.reschedule(reminders)
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun repository(): CalendarRepository
        fun scheduler(): ReminderScheduler
    }
}
