package app.foscal.core.model

import java.time.ZoneId

/**
 * A reminder that has been resolved against its event and is ready to be scheduled
 * with the system [android.app.AlarmManager].
 */
data class ScheduledReminder(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    /**
     * The raw `Instances.BEGIN` of the occurrence. This is an *identity*, not a display value: it
     * keys the alarm and looks the occurrence back up in the Instances table, so it must stay
     * exactly as the provider reported it — including the UTC midnight all-day events are stored
     * at. Use [allDay] to decide how to present it.
     */
    val startMillis: Long,
    val minutesBefore: Int,
    val allDay: Boolean,
    /** Wall-clock instant the notification should fire. See [ReminderTrigger.triggerAtMillis]. */
    val triggerAtMillis: Long,
) {
    companion object {
        /**
         * Builds a reminder with its trigger derived from [zone]. Prefer this over the constructor:
         * the all-day case is not a subtraction, and getting it wrong is silent.
         */
        fun create(
            eventId: Long,
            calendarId: Long,
            title: String,
            location: String?,
            startMillis: Long,
            minutesBefore: Int,
            allDay: Boolean,
            zone: ZoneId,
        ): ScheduledReminder = ScheduledReminder(
            eventId = eventId,
            calendarId = calendarId,
            title = title,
            location = location,
            startMillis = startMillis,
            minutesBefore = minutesBefore,
            allDay = allDay,
            triggerAtMillis = ReminderTrigger.triggerAtMillis(
                startMillis = startMillis,
                allDay = allDay,
                minutesBefore = minutesBefore,
                zone = zone,
            ),
        )
    }
}
