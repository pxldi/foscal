package app.calendarium.core.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.Event
import app.calendarium.core.model.EventInput
import app.calendarium.core.model.Frequency
import app.calendarium.core.model.ScheduledReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads calendars and events from the Android system [CalendarContract] provider.
 *
 * On Android the system calendar provider is the single source of truth that other apps
 * (DAVx5 for Nextcloud/ownCloud CalDAV, Google, Exchange, local calendars) write to.
 * Calendarium reads and writes through this contract, so any sync adapter the user
 * installs keeps everything in sync automatically — no CalDAV code in this app.
 */
interface CalendarRepository {

    fun getCalendarUri(calendarId: Long): Uri

    suspend fun getCalendars(): List<Calendar>

    suspend fun getEvents(calendarIds: Set<Long>, from: Instant, to: Instant): List<Event>

    /** Emits the current list of calendars, then re-emits whenever the provider changes. */
    fun observeCalendars(): Flow<List<Calendar>>

    fun observeEvents(calendarIds: Set<Long>, from: Instant, to: Instant): Flow<List<Event>>

    /** Creates a fully local (offline) calendar that only this device holds. */
    suspend fun createLocalCalendar(name: String, color: Int): Long?

    suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean)

    suspend fun createEvent(input: EventInput): Long?

    suspend fun updateEvent(eventId: Long, input: EventInput): Boolean

    suspend fun deleteEvent(eventId: Long): Boolean

    suspend fun getReminderMinutes(eventId: Long): List<Int>

    suspend fun getUpcomingReminders(from: Instant, to: Instant): List<ScheduledReminder>
}

@Singleton
class CalendarContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    private fun safeQuery(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor? = try {
        safeQuery(uri, projection, selection, selectionArgs, sortOrder)
    } catch (_: SecurityException) {
        null
    }

    private fun safeInsert(uri: Uri, values: ContentValues): Uri? = try {
        safeInsert(uri, values)
    } catch (_: SecurityException) {
        null
    }

    private fun safeUpdate(uri: Uri, values: ContentValues?, where: String?, args: Array<String>?): Int = try {
        safeUpdate(uri, values, where, args)
    } catch (_: SecurityException) {
        0
    }

    private fun safeDelete(uri: Uri, where: String?, args: Array<String>?): Int = try {
        safeDelete(uri, where, args)
    } catch (_: SecurityException) {
        0
    }

    override fun getCalendarUri(calendarId: Long): Uri =
        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)

    override suspend fun getCalendars(): List<Calendar> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        val out = mutableListOf<Calendar>()
        safeQuery(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += Calendar(
                    id = c.getLong(0),
                    displayName = c.getString(1).orEmpty(),
                    accountName = c.getString(2).orEmpty(),
                    accountType = c.getString(3).orEmpty(),
                    ownerName = c.getString(4),
                    color = c.getInt(5),
                    visible = c.getInt(6) == 1,
                    syncEnabled = c.getInt(7) == 1,
                )
            }
        }
        out
    }

    override suspend fun getEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event> = withContext(Dispatchers.IO) {
        if (calendarIds.isEmpty()) return@withContext emptyList()
        queryInstances(calendarIds, from, to)
    }

    override fun observeCalendars(): Flow<List<Calendar>> =
        contentChanges(CalendarContract.Calendars.CONTENT_URI)
            .onStart { emit(Unit) }
            .map { getCalendars() }
            .flowOn(Dispatchers.IO)

    override fun observeEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): Flow<List<Event>> =
        contentChanges(CalendarContract.Events.CONTENT_URI)
            .onStart { emit(Unit) }
            .map { getEvents(calendarIds, from, to) }
            .flowOn(Dispatchers.IO)

    override suspend fun createLocalCalendar(name: String, color: Int): Long? =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, name)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                put(CalendarContract.Calendars.CALENDAR_COLOR, color)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            }
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            safeInsert(uri, values)?.let { ContentUris.parseId(it) }
        }

    override suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.VISIBLE, if (hidden) 0 else 1)
            }
            safeUpdate(getCalendarUri(calendarId), values, null, null)
        }
    }

    override suspend fun createEvent(input: EventInput): Long? = withContext(Dispatchers.IO) {
        val values = eventToContentValues(input)
        val newId = safeInsert(CalendarContract.Events.CONTENT_URI, values)
            ?.let { ContentUris.parseId(it) } ?: return@withContext null
        setReminder(newId, input.reminderMinutesBefore)
        newId
    }

    override suspend fun updateEvent(eventId: Long, input: EventInput): Boolean =
        withContext(Dispatchers.IO) {
            val values = eventToContentValues(input)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val rows = safeUpdate(uri, values, null, null)
            if (rows > 0) {
                safeDelete(
                    CalendarContract.Reminders.CONTENT_URI,
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString()),
                )
                setReminder(eventId, input.reminderMinutesBefore)
                true
            } else {
                false
            }
        }

    override suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        safeDelete(uri, null, null) > 0
    }

    override suspend fun getReminderMinutes(eventId: Long): List<Int> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Int>()
            safeQuery(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) out += c.getInt(0)
            }
            out
        }

    override suspend fun getUpcomingReminders(
        from: Instant,
        to: Instant,
    ): List<ScheduledReminder> = withContext(Dispatchers.IO) {
        val calendarIds = getCalendars().map { it.id }.toSet()
        if (calendarIds.isEmpty()) return@withContext emptyList()
        val events = getEvents(calendarIds, from, to)
        val now = System.currentTimeMillis()
        events.flatMap { event ->
            val reminders = safeQuery(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(event.id.toString()),
                null,
            )?.use { c ->
                buildList { while (c.moveToNext()) add(c.getInt(0)) }
            }.orEmpty()
            reminders.mapNotNull { minutes ->
                val trigger = event.start.toEpochMilli() - minutes * 60_000L
                if (trigger <= now) return@mapNotNull null
                ScheduledReminder(
                    eventId = event.id,
                    calendarId = event.calendarId,
                    title = event.title,
                    location = event.location,
                    startMillis = event.start.toEpochMilli(),
                    minutesBefore = minutes,
                )
            }
        }
    }

    private fun setReminder(eventId: Long, minutesBefore: Int?) {
        if (minutesBefore == null) return
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        safeInsert(CalendarContract.Reminders.CONTENT_URI, values)
    }

    private fun eventToContentValues(input: EventInput): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, input.calendarId)
        put(CalendarContract.Events.TITLE, input.title.trim().ifEmpty { "(Untitled)" })
        put(CalendarContract.Events.EVENT_LOCATION, input.location)
        put(CalendarContract.Events.DESCRIPTION, input.description)
        put(CalendarContract.Events.ALL_DAY, if (input.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, input.timezone)
        put(CalendarContract.Events.DTSTART, input.start.toEpochMilli())

        if (input.frequency == Frequency.NONE) {
            // Non-recurring: provider requires DTEND (or DURATION), forbids RRULE.
            put(CalendarContract.Events.DTEND, input.end.toEpochMilli())
            putNull(CalendarContract.Events.RRULE)
            putNull(CalendarContract.Events.DURATION)
        } else {
            // Recurring: provider requires DURATION, forbids DTEND.
            put(CalendarContract.Events.DURATION, formatDuration(input.start, input.end, input.allDay))
            put(CalendarContract.Events.RRULE, "FREQ=${input.frequency.name}")
            putNull(CalendarContract.Events.DTEND)
        }
    }

    /** RFC 5545 duration (P[n]DT[n]H[n]M[n]S, or P[n]W for weeks, or P[n]D for all-day). */
    private fun formatDuration(start: Instant, end: Instant, allDay: Boolean): String {
        val totalSeconds = (end.toEpochMilli() - start.toEpochMilli()) / 1000
        if (allDay) {
            val days = ((totalSeconds + 86_400 / 2) / 86_400).coerceAtLeast(1)
            return "P${days}D"
        }
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            append('P')
            if (days > 0) append("${days}D")
            if (hours > 0 || minutes > 0 || seconds > 0) {
                append('T')
                if (hours > 0) append("${hours}H")
                if (minutes > 0) append("${minutes}M")
                if (seconds > 0) append("${seconds}S")
            }
            if (length == 1) append("T0S") // non-empty body required
        }
    }

    private fun queryInstances(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
        )
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
        val args = calendarIds.map { it.toString() }.toTypedArray()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.toEpochMilli())
        ContentUris.appendId(builder, to.toEpochMilli())
        val out = mutableListOf<Event>()
        safeQuery(
            builder.build(),
            projection,
            selection,
            args,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2).orEmpty()
                if (title.isBlank()) continue
                out += Event(
                    id = c.getLong(0),
                    calendarId = c.getLong(1),
                    title = title,
                    location = c.getString(3),
                    description = c.getString(4),
                    start = Instant.ofEpochMilli(c.getLong(5)),
                    end = Instant.ofEpochMilli(c.getLong(6)),
                    allDay = c.getInt(7) == 1,
                    timezone = c.getString(8),
                )
            }
        }
        return out
    }

    private fun contentChanges(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(uri, /* notifyForDescendants = */ true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val LOCAL_ACCOUNT_NAME = "Calendarium"
    }
}
