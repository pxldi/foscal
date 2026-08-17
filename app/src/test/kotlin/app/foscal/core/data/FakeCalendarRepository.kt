package app.foscal.core.data

import android.net.Uri
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.ScheduledReminder
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

    /** Overrides what [getEventsForExport] returns; null falls back to the [events] fixture. */
    var exportEvents: List<ExportEvent>? = null

    /** Every event created, in order — import writes many rows in one call. */
    val created = mutableListOf<EventInput>()

    /** Every single-occurrence override written, as (masterId, originalInstanceTime, values). */
    val instanceUpdates = mutableListOf<Triple<Long, Long, EventInput>>()

    /** Every single-occurrence cancellation, as (masterId, originalInstanceTime). */
    val instanceDeletes = mutableListOf<Pair<Long, Long>>()

    private var nextEventId = 1L

    fun reset() {
        lastOp = null
        lastRebaseCount = null
        lastCreated = null
        lastWritten = null
        created.clear()
        instanceUpdates.clear()
        instanceDeletes.clear()
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

    override fun observeEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): Flow<List<Event>> = MutableStateFlow(
        events.filter { it.calendarId in calendarIds && it.start <= to && it.end >= from },
    )

    override suspend fun ensureLocalCalendar(name: String, color: Int): Long? = 1L

    override suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean) = Unit

    override suspend fun createEvent(input: EventInput): Long? {
        lastOp = Op.CREATE
        lastCreated = input
        lastWritten = input
        created += input
        // Distinct ids per call: an import creates several masters and then addresses each by the
        // id it got back, which a constant would collapse into one.
        return nextEventId++
    }

    override suspend fun updateEvent(eventId: Long, input: EventInput): Boolean {
        lastOp = Op.UPDATE
        lastWritten = input
        return true
    }

    override suspend fun deleteEvent(eventId: Long): Boolean {
        lastOp = Op.DELETE
        return true
    }

    override suspend fun updateEventInstance(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
    ): Boolean {
        lastOp = Op.UPDATE_INSTANCE
        lastWritten = input
        instanceUpdates += Triple(eventId, instanceStartMillis, input)
        return true
    }

    override suspend fun updateEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
        rebaseCount: Boolean,
    ): Boolean {
        lastOp = Op.UPDATE_FOLLOWING
        lastRebaseCount = rebaseCount
        lastCreated = input
        lastWritten = input
        return true
    }

    override suspend fun deleteEventInstance(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_INSTANCE
        instanceDeletes += eventId to instanceStartMillis
        return true
    }

    override suspend fun deleteEventFollowing(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_FOLLOWING
        return true
    }

    override suspend fun getReminderMinutes(eventId: Long): List<Int> = reminderMinutes

    override suspend fun getReminderMinutesFor(
        eventIds: Collection<Long>,
    ): Map<Long, List<Int>> = eventIds.associateWith { reminderMinutes }

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
