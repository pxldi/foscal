package app.foscal.core.data

import android.provider.CalendarContract
import app.foscal.core.model.Attendee
import app.foscal.core.model.Calendar

/** One `Attendees` row, reduced to what deciding "did the user decline this" needs. */
internal data class AttendeeStatusRow(
    val eventId: Long,
    val email: String?,
    val status: Int,
)

/**
 * Which events the user has declined, judged from their own row in the `Attendees` table.
 *
 * `Events.SELF_ATTENDEE_STATUS` would be the obvious column, but the provider only recomputes it
 * when the calendar's owner address matches the attendee row exactly, case included, so a reply
 * written from here can leave it stale. The row itself is what both this app and a sync adapter
 * write, and it is compared through [Attendee.normalizeAddress] exactly as the reply path does.
 */
internal object DeclinedEvents {

    /** The address the user is known by on [calendar], normalized, or null if it has none. */
    fun selfAddress(calendar: Calendar): String? =
        (calendar.ownerName?.takeIf { it.isNotBlank() } ?: calendar.accountName.takeIf { it.isNotBlank() })
            ?.let(Attendee::normalizeAddress)

    fun find(
        rows: List<AttendeeStatusRow>,
        calendarOfEvent: Map<Long, Long>,
        calendars: List<Calendar>,
    ): Set<Long> {
        val selfByCalendar = calendars.mapNotNull { cal -> selfAddress(cal)?.let { cal.id to it } }.toMap()
        return rows.asSequence()
            .filter { it.status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED }
            .filter { row ->
                val email = row.email ?: return@filter false
                val self = calendarOfEvent[row.eventId]?.let(selfByCalendar::get) ?: return@filter false
                Attendee.normalizeAddress(email) == self
            }
            .map { it.eventId }
            .toSet()
    }
}
