package app.foscal.core.data

import android.net.Uri
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.ScheduledReminder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * In-memory [CalendarRepository] for unit tests. Records the most recent mutation so view-model
 * tests can assert which code path (create / instance / following / whole-series) was taken.
 */
class FakeCalendarRepository(
    private val calendars: List<Calendar> = emptyList(),
    private val events: List<Event> = emptyList(),
    private val reminderMinutes: List<Int> = emptyList(),
    private val attendees: List<Attendee> = emptyList(),
) : CalendarRepository {

    enum class Op { CREATE, UPDATE, UPDATE_INSTANCE, UPDATE_FOLLOWING, DELETE, DELETE_INSTANCE, DELETE_FOLLOWING }

    var lastOp: Op? = null
        private set
    var lastRebaseCount: Boolean? = null
        private set
    var lastCreated: EventInput? = null
        private set

    /** The [EventInput] handed to the most recent write, whichever path it took. */
    var lastWritten: EventInput? = null
        private set

    /** When true, every event write is refused the way the provider refuses one. */
    var refuseWrites = false

    /** Overrides what [getEventsForExport] returns; null falls back to the [events] fixture. */
    var exportEvents: List<ExportEvent>? = null

    /** Every event created, in order — import writes many rows in one call. */
    val created = mutableListOf<EventInput>()

    /** Every single-occurrence override written, as (masterId, originalInstanceTime, values). */
    val instanceUpdates = mutableListOf<Triple<Long, Long, EventInput>>()

    /** Every single-occurrence cancellation, as (masterId, originalInstanceTime). */
    val instanceDeletes = mutableListOf<Pair<Long, Long>>()

    /** Every whole-event delete attempted, in order, refused or not. */
    val deletedIds = mutableListOf<Long>()

    /** Events whose whole-event delete is refused, while every other write goes through. */
    var refuseDeleteOf: Set<Long> = emptySet()

    /** How long a whole-event delete takes, so a test can look at the middle of one. */
    var deleteDelayMillis = 0L

    private var nextEventId = 1L

    fun reset() {
        lastOp = null
        lastRebaseCount = null
        lastCreated = null
        lastWritten = null
        created.clear()
        instanceUpdates.clear()
        instanceDeletes.clear()
        observedWindows.clear()
    }

    override fun getCalendarUri(calendarId: Long): Uri = Uri.EMPTY

    override suspend fun getCalendars(): List<Calendar> = calendars

    // The window is honoured, not ignored: the Instances table only ever returns occurrences
    // overlapping it, and a fake that hands back everything hides exactly the bug where a caller
    // looks an event up by scanning too narrow a range.
    override suspend fun getEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event> = events.filter {
        it.calendarId in calendarIds && it.start <= to && it.end >= from
    }

    override suspend fun getEventOccurrence(eventId: Long, instanceStartMillis: Long): Event? {
        val matches = events.filter { it.id == eventId }
        return matches.firstOrNull { it.start.toEpochMilli() == instanceStartMillis }
            ?: matches.firstOrNull()
    }

    override suspend fun searchEvents(
        calendarIds: Set<Long>,
        query: String,
        from: Instant,
        to: Instant,
    ): List<Event> = events.filter {
        it.calendarId in calendarIds &&
            it.start >= from &&
            it.start <= to &&
            it.title.contains(query, ignoreCase = true)
    }

    override suspend fun getRecentLocations(limit: Int): List<String> =
        events.sortedByDescending { it.start }
            .mapNotNull { it.location?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(limit)

    override fun observeCalendars(): Flow<List<Calendar>> = MutableStateFlow(calendars)

    /**
     * Every window [observeEvents] was asked for, in order.
     *
     * Recorded because the cost of paging is not what the screen shows but how often it goes back
     * to the provider for it: a real Instances query expands recurrences across every calendar,
     * and a page turn that triggers one lands the result partway through its own animation.
     */
    val observedWindows = mutableListOf<Pair<Instant, Instant>>()

    override fun observeEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): Flow<List<Event>> {
        observedWindows += from to to
        return MutableStateFlow(
            events.filter { it.calendarId in calendarIds && it.start <= to && it.end >= from },
        )
    }

    override suspend fun ensureLocalCalendar(name: String, color: Int): Long? = 1L

    /** Names handed to [createLocalCalendar], in order. */
    val createdCalendars = mutableListOf<Pair<String, Int>>()

    /** What [getEventColor] should answer, per event id. */
    val eventColors = mutableMapOf<Long, Int>()

    override suspend fun getEventColor(eventId: Long): Int? = eventColors[eventId]

    /** Replies handed to [setSelfAttendeeStatus], in order. */
    val replies = mutableListOf<Pair<Long, AttendeeStatus>>()

    override suspend fun setSelfAttendeeStatus(eventId: Long, status: AttendeeStatus): Boolean {
        replies += eventId to status
        return true
    }

    /** Every edit handed to [updateLocalCalendar], in order. */
    val updatedCalendars = mutableListOf<Triple<Long, String, Int>>()

    override suspend fun updateLocalCalendar(calendarId: Long, name: String, color: Int): Boolean {
        updatedCalendars += Triple(calendarId, name, color)
        return calendars.any { it.id == calendarId && it.isLocal }
    }

    /** Ids handed to [deleteLocalCalendar], in order. */
    val deletedCalendars = mutableListOf<Long>()

    /** What [countEvents] should answer, per calendar id. */
    val eventCounts = mutableMapOf<Long, Int>()

    override suspend fun countEvents(calendarId: Long): Int = eventCounts[calendarId] ?: 0

    override suspend fun deleteLocalCalendar(calendarId: Long): Boolean {
        deletedCalendars += calendarId
        return calendars.any { it.id == calendarId && it.isLocal }
    }

    override suspend fun createLocalCalendar(name: String, color: Int): Long? {
        createdCalendars += name to color
        return 100L + createdCalendars.size
    }

    override suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean) = Unit

    override suspend fun createEvent(input: EventInput): Long? {
        lastOp = Op.CREATE
        lastCreated = input
        lastWritten = input
        created += input
        if (refuseWrites) return null
        // Distinct ids per call: an import creates several masters and then addresses each by the
        // id it got back, which a constant would collapse into one.
        return nextEventId++
    }

    override suspend fun updateEvent(eventId: Long, input: EventInput): Boolean {
        lastOp = Op.UPDATE
        lastWritten = input
        return !refuseWrites
    }

    override suspend fun deleteEvent(eventId: Long): Boolean {
        lastOp = Op.DELETE
        if (deleteDelayMillis > 0) delay(deleteDelayMillis)
        deletedIds += eventId
        return !refuseWrites && eventId !in refuseDeleteOf
    }

    override suspend fun updateEventInstance(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
    ): Boolean {
        lastOp = Op.UPDATE_INSTANCE
        lastWritten = input
        instanceUpdates += Triple(eventId, instanceStartMillis, input)
        return !refuseWrites
    }

    override suspend fun updateEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
        rebaseCount: Boolean,
    ): Boolean {
        lastOp = Op.UPDATE_FOLLOWING
        lastRebaseCount = rebaseCount
        followingUpdates += Triple(eventId, instanceStartMillis, input)
        lastCreated = input
        lastWritten = input
        return !refuseWrites
    }

    override suspend fun deleteEventInstance(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_INSTANCE
        instanceDeletes += eventId to instanceStartMillis
        return !refuseWrites
    }

    override suspend fun deleteEventFollowing(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_FOLLOWING
        return !refuseWrites
    }

    override suspend fun getReminderMinutes(eventId: Long): List<Int> = reminderMinutes

    override suspend fun getReminderMinutesFor(
        eventIds: Collection<Long>,
        notifiableOnly: Boolean,
    ): Map<Long, List<Int>> = eventIds.associateWith { reminderMinutes }

    /** UIDs that count as already on the calendar, for import dedupe tests. */
    val existingUids = mutableSetOf<String>()

    override suspend fun findEventUids(calendarId: Long, uids: Collection<String>): Set<String> =
        uids.filter { it in existingUids }.toSet()

    /** Every `updateEventFollowing` call, as (eventId, split start, input). */
    val followingUpdates = mutableListOf<Triple<Long, Long, EventInput>>()

    override suspend fun getAttendees(eventId: Long): List<Attendee> = attendees

    override suspend fun getAttendeesFor(
        eventIds: Collection<Long>,
    ): Map<Long, List<Attendee>> = eventIds.associateWith { attendees }

    // Mirrors the real read: master rows only, so a recurring series contributes one event and not
    // one per occurrence. Test fixtures hold masters already, so this is just the id filter.
    // [exportEvents] overrides it for tests that need a series with exceptions attached.
    override suspend fun getEventsForExport(calendarIds: Set<Long>): List<ExportEvent> =
        exportEvents
            ?: events.filter { it.calendarId in calendarIds }.distinctBy { it.id }
                .map { ExportEvent(it) }

    override suspend fun getUpcomingReminders(
        from: Instant,
        to: Instant,
        zone: ZoneId,
        excludedCalendarIds: Set<Long>,
    ): List<ScheduledReminder>? = emptyList()

    override suspend fun getLargestReminderOffsetMinutes(): Int = reminderMinutes.maxOrNull() ?: 0
}
