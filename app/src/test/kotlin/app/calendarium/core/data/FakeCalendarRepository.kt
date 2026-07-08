package app.calendarium.core.data

import android.net.Uri
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.Event
import app.calendarium.core.model.EventInput
import app.calendarium.core.model.ScheduledReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

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

    fun reset() {
        lastOp = null
        lastRebaseCount = null
        lastCreated = null
    }

    override fun getCalendarUri(calendarId: Long): Uri = Uri.EMPTY

    override suspend fun getCalendars(): List<Calendar> = calendars

    override suspend fun getEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event> = events.filter { it.calendarId in calendarIds }

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
    ): Flow<List<Event>> = MutableStateFlow(events.filter { it.calendarId in calendarIds })

    override suspend fun createLocalCalendar(name: String, color: Int): Long? = 1L

    override suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean) = Unit

    override suspend fun createEvent(input: EventInput): Long? {
        lastOp = Op.CREATE
        lastCreated = input
        return 1L
    }

    override suspend fun updateEvent(eventId: Long, input: EventInput): Boolean {
        lastOp = Op.UPDATE
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
        return true
    }

    override suspend fun deleteEventInstance(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_INSTANCE
        return true
    }

    override suspend fun deleteEventFollowing(eventId: Long, instanceStartMillis: Long): Boolean {
        lastOp = Op.DELETE_FOLLOWING
        return true
    }

    override suspend fun getReminderMinutes(eventId: Long): List<Int> = reminderMinutes

    override suspend fun getUpcomingReminders(from: Instant, to: Instant): List<ScheduledReminder> =
        emptyList()
}
