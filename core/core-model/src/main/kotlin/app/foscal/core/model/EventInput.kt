package app.foscal.core.model

import java.time.Instant
import java.time.ZoneId

enum class Frequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

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
)
