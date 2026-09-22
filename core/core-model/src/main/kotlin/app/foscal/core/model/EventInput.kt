package app.foscal.core.model

import java.time.Instant
import java.time.ZoneId

enum class Frequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

/** Who may see an event's details: iCalendar CLASS, the provider's `ACCESS_LEVEL`. */
enum class EventAccess { PUBLIC, PRIVATE, CONFIDENTIAL }

/** Whether an event blocks time: iCalendar TRANSP, the provider's `AVAILABILITY`. */
enum class EventAvailability { BUSY, FREE }

/**
 * The zone to persist for an event that should stay anchored to [original].
 *
 * Never re-anchor a stored event to the device zone: doing so keeps the instant the user picked
 * but shifts recurrence expansion for every other client on the same CalDAV calendar. The
 * device zone is only correct when there is no original ([fallback]), or when the stored one is
 * unparseable — the provider rejects a junk `EVENT_TIMEZONE` outright, which would lose the whole
 * write.
 */
fun resolveEventTimezone(original: String?, fallback: ZoneId): String =
    original?.takeIf { it.isNotBlank() && runCatching { ZoneId.of(it) }.isSuccess } ?: fallback.id

/**
 * Editable form of an event, used when creating or updating via [CalendarRepository].
 *
 * For recurring events, [end] is used only to compute the per-instance duration; the
 * stored event uses DURATION + RRULE per the iCalendar spec (and Android's Calendar Provider
 * requires exactly that — DURATION is forbidden with DTEND and vice-versa).
 */
data class EventInput(
    val calendarId: Long,
    val title: String,
    val location: String?,
    val description: String?,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val timezone: String,
    val frequency: Frequency = Frequency.NONE,
    /**
     * The verbatim RRULE to persist. When non-null it is written as-is, preserving details we
     * don't model in [frequency] (BYDAY, INTERVAL, UNTIL, …) for events synced from CalDAV.
     * When null, a simple rule is derived from [frequency].
     */
    val rrule: String? = null,
    /**
     * Every reminder on the event, in minutes before its start. Written verbatim, so an event that
     * arrived from CalDAV with several alarms keeps all of them across an unrelated edit — the
     * provider has no partial-update path for reminders, so a caller that supplies only one would
     * silently drop the rest. Empty means no reminders.
     */
    val reminderMinutes: List<Int> = listOf(15),
    /**
     * The complete guest list to write, or null to leave whatever the event already has alone.
     *
     * Attendees are all-or-nothing for the same reason reminders are — the provider has no partial
     * update for the `Attendees` table, so writing means clearing and reinserting. Unlike
     * reminders, though, most callers have no guest list at all (quick add, a drag-to-move on the
     * week grid, an `.ics` file with no ATTENDEE lines), and passing an empty list from those would
     * silently drop every guest DAVx⁵ synced down. Null is that "don't touch" case; only a caller
     * that actually loaded the guests may pass a list, empty or not.
     */
    val attendees: List<Attendee>? = null,
    /**
     * A colour for this one event, or null to follow its calendar's.
     *
     * Written straight to `EVENT_COLOR`, which the provider takes from an ordinary app and which
     * `Instances.DISPLAY_COLOR` already prefers over the calendar's — so nothing downstream has to
     * know this exists. What a *sync adapter* then does with it is its own business: CalDAV has a
     * per-event COLOR (RFC 7986) that not every server or client round-trips, and palette-based
     * accounts may snap it to their nearest swatch or drop it.
     */
    val color: Int? = null,
)
