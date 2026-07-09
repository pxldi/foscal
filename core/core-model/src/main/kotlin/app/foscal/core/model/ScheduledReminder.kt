package app.foscal.core.model

/**
 * A reminder that has been resolved against its event and is ready to be scheduled
 * with the system [android.app.AlarmManager]. [triggerAtMillis] is the wall-clock
 * instant at which the notification should fire.
 */
data class ScheduledReminder(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    val startMillis: Long,
    val minutesBefore: Int,
) {
    val triggerAtMillis: Long
        get() = startMillis - minutesBefore * 60_000L
}
