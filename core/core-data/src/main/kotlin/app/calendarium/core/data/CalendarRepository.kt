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
import app.calendarium.core.model.RecurrenceRules
import app.calendarium.core.model.ScheduledReminder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
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

    /**
     * Full-text-ish search over event title/location/description across a wide window.
     * Recurring events collapse to a single result (the next upcoming occurrence, or the last
     * past one) so a frequent series doesn't flood the list.
     */
    suspend fun searchEvents(
        calendarIds: Set<Long>,
        query: String,
        from: Instant,
        to: Instant,
    ): List<Event>

    /**
     * Distinct non-blank event locations the user has used before, most-recently-used first.
     * Backs the editor's offline location autocomplete — suggestions come only from the user's
     * own history, so nothing leaves the device.
     */
    suspend fun getRecentLocations(limit: Int = 50): List<String>

    /** Emits the current list of calendars, then re-emits whenever the provider changes. */
    fun observeCalendars(): Flow<List<Calendar>>

    fun observeEvents(calendarIds: Set<Long>, from: Instant, to: Instant): Flow<List<Event>>

    /** Creates a fully local (offline) calendar that only this device holds. */
    suspend fun createLocalCalendar(name: String, color: Int): Long?

    suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean)

    suspend fun createEvent(input: EventInput): Long?

    suspend fun updateEvent(eventId: Long, input: EventInput): Boolean

    suspend fun deleteEvent(eventId: Long): Boolean

    /**
     * Overrides a single occurrence of a recurring event (the instance beginning at
     * [instanceStartMillis]) with [input], leaving the rest of the series untouched.
     */
    suspend fun updateEventInstance(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
    ): Boolean

    /** Cancels a single occurrence of a recurring event, leaving the rest of the series. */
    suspend fun deleteEventInstance(eventId: Long, instanceStartMillis: Long): Boolean

    /**
     * Applies [input] to the occurrence at [instanceStartMillis] and every occurrence after it:
     * truncates the original series to end just before [instanceStartMillis] and creates a new
     * recurring event from [input] onward. When [rebaseCount] is true (the user did not change
     * the recurrence rule) and the original series used COUNT, the following series' count is
     * reduced by the number of occurrences that already passed, preserving the overall length.
     */
    suspend fun updateEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
        rebaseCount: Boolean = false,
    ): Boolean

    /** Cancels the occurrence at [instanceStartMillis] and every one after it by truncating
     *  the series to end just before [instanceStartMillis]. */
    suspend fun deleteEventFollowing(eventId: Long, instanceStartMillis: Long): Boolean

    suspend fun getReminderMinutes(eventId: Long): List<Int>

    suspend fun getUpcomingReminders(from: Instant, to: Instant): List<ScheduledReminder>
}

@Singleton
class CalendarContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permission: CalendarPermissionState,
) : CalendarRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    // The Calendar Provider throws SecurityException before permission is granted and
    // IllegalArgumentException for values it rejects (e.g. malformed recurrence exceptions).
    // Neither should ever crash the app — callers treat a null/0 result as "operation failed".
    private fun safeQuery(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): android.database.Cursor? = try {
        resolver.query(uri, projection, selection, selectionArgs, sortOrder)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun safeInsert(uri: Uri, values: ContentValues): Uri? = try {
        resolver.insert(uri, values)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun safeUpdate(uri: Uri, values: ContentValues?, where: String?, args: Array<String>?): Int = try {
        resolver.update(uri, values, where, args)
    } catch (_: SecurityException) {
        0
    } catch (_: IllegalArgumentException) {
        0
    }

    private fun safeDelete(uri: Uri, where: String?, args: Array<String>?): Int = try {
        resolver.delete(uri, where, args)
    } catch (_: SecurityException) {
        0
    } catch (_: IllegalArgumentException) {
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

    override suspend fun searchEvents(
        calendarIds: Set<Long>,
        query: String,
        from: Instant,
        to: Instant,
    ): List<Event> = withContext(Dispatchers.IO) {
        val needle = query.trim().lowercase()
        if (calendarIds.isEmpty() || needle.isEmpty()) return@withContext emptyList()
        val now = Instant.now()
        val matched = queryInstances(calendarIds, from, to).filter { e ->
            e.title.lowercase().contains(needle) ||
                e.location?.lowercase()?.contains(needle) == true ||
                e.description?.lowercase()?.contains(needle) == true
        }
        // Collapse recurring series to one row: the next upcoming occurrence, else the last past one.
        matched.groupBy { it.id }
            .mapValues { (_, instances) ->
                instances.firstOrNull { !it.start.isBefore(now) } ?: instances.last()
            }
            .values
            .sortedBy { it.start }
    }

    override suspend fun getRecentLocations(limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            // Read straight from the Events table (not Instances) newest-first and dedupe in code —
            // the provider has no DISTINCT — collecting locations until we have [limit] unique ones.
            val seen = LinkedHashSet<String>()
            safeQuery(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.EVENT_LOCATION),
                "${CalendarContract.Events.EVENT_LOCATION} IS NOT NULL AND " +
                    "${CalendarContract.Events.EVENT_LOCATION} != ''",
                null,
                "${CalendarContract.Events.DTSTART} DESC",
            )?.use { c ->
                while (c.moveToNext() && seen.size < limit) {
                    val location = c.getString(0)?.trim().orEmpty()
                    if (location.isNotEmpty()) seen += location
                }
            }
            seen.toList()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCalendars(): Flow<List<Calendar>> =
        permission.granted.flatMapLatest { granted ->
            if (!granted) {
                flowOf(emptyList())
            } else {
                contentChanges(CalendarContract.Calendars.CONTENT_URI)
                    .onStart { emit(Unit) }
                    .map { getCalendars() }
            }
        }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): Flow<List<Event>> =
        permission.granted.flatMapLatest { granted ->
            if (!granted) {
                flowOf(emptyList())
            } else {
                contentChanges(CalendarContract.Events.CONTENT_URI)
                    .onStart { emit(Unit) }
                    .map { getEvents(calendarIds, from, to) }
            }
        }.flowOn(Dispatchers.IO)

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
        // AOSP links a recurrence exception to its master through the master's _sync_id. Events on
        // local calendars have no sync adapter to assign one, so we mint it ourselves — without it,
        // inserting an exception silently wipes the rest of the series. CalDAV calendars are left
        // alone; DAVx⁵ owns their sync ids.
        ensureLocalSyncId(newId, input.calendarId)
        newId
    }

    private fun ensureLocalSyncId(eventId: Long, calendarId: Long) {
        val account = localAccountFor(calendarId) ?: return
        val values = ContentValues().apply {
            put(CalendarContract.Events._SYNC_ID, "calendarium-${java.util.UUID.randomUUID()}")
        }
        val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Events.ACCOUNT_NAME, account.first)
            .appendQueryParameter(CalendarContract.Events.ACCOUNT_TYPE, account.second)
            .build()
        safeUpdate(
            uri,
            values,
            "${CalendarContract.Events._ID} = ?",
            arrayOf(eventId.toString()),
        )
    }

    /** Returns (accountName, accountType) if the calendar is a local (non-synced) one, else null. */
    private fun localAccountFor(calendarId: Long): Pair<String, String>? {
        return safeQuery(
            getCalendarUri(calendarId),
            arrayOf(
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: return@use null
            val type = c.getString(1) ?: return@use null
            if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) name to type else null
        }
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

    override suspend fun updateEventInstance(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
    ): Boolean = withContext(Dispatchers.IO) {
        // The exception overrides the single occurrence identified by ORIGINAL_INSTANCE_TIME.
        // The provider derives the base series from DURATION, so an exception must express its
        // length as DURATION too — supplying DTEND is rejected ("Exceptions can't overwrite dtend").
        // CALENDAR_ID is likewise fixed by the series and must not be set here.
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceStartMillis)
            put(CalendarContract.Events.TITLE, input.title.trim().ifEmpty { "(Untitled)" })
            put(CalendarContract.Events.EVENT_LOCATION, input.location)
            put(CalendarContract.Events.DESCRIPTION, input.description)
            put(CalendarContract.Events.ALL_DAY, if (input.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, input.timezone)
            put(CalendarContract.Events.DTSTART, input.start.toEpochMilli())
            put(
                CalendarContract.Events.DURATION,
                formatDuration(input.start, input.end, input.allDay),
            )
        }
        val uri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            eventId,
        )
        val result = safeInsert(uri, values) ?: return@withContext false
        val newId = ContentUris.parseId(result)
        if (newId > 0) setReminder(newId, input.reminderMinutesBefore)
        true
    }

    override suspend fun deleteEventInstance(
        eventId: Long,
        instanceStartMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instanceStartMillis)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
        }
        val uri = ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            eventId,
        )
        safeInsert(uri, values) != null
    }

    override suspend fun updateEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
        input: EventInput,
        rebaseCount: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val master = loadMaster(eventId) ?: return@withContext false
        val occurrencesBefore = countInstancesBefore(eventId, master.dtStart, instanceStartMillis)
        truncateSeries(eventId, master, instanceStartMillis, occurrencesBefore)
        // The following series takes the user's edited values. When the recurrence rule was left
        // untouched, preserve the original pattern but rebase a COUNT end so the series length is
        // preserved; when the user changed recurrence, apply their rule verbatim from [input].
        val followingRrule: String?
        val followingFrequency: Frequency
        if (rebaseCount) {
            val masterSpec = RecurrenceRules.parse(master.rrule)
            followingFrequency = masterSpec.frequency
            followingRrule = RecurrenceRules.rebaseFollowing(
                master.rrule,
                occurrencesBefore,
                master.allDay,
                master.zone(),
            )
        } else {
            followingFrequency = input.frequency
            followingRrule = input.rrule
        }
        createEvent(input.copy(frequency = followingFrequency, rrule = followingRrule))
        true
    }

    override suspend fun deleteEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val master = loadMaster(eventId) ?: return@withContext false
        val occurrencesBefore = countInstancesBefore(eventId, master.dtStart, instanceStartMillis)
        truncateSeries(eventId, master, instanceStartMillis, occurrencesBefore)
    }

    /** Holds the recurrence-relevant columns of a master event (read from the Events table). */
    private data class MasterEvent(
        val dtStart: Long,
        val allDay: Boolean,
        val timezone: String?,
        val rrule: String?,
    ) {
        fun zone(): ZoneId = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
    }

    private fun loadMaster(eventId: Long): MasterEvent? {
        val projection = arrayOf(
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
        )
        return safeQuery(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            projection,
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            MasterEvent(
                dtStart = c.getLong(0),
                allDay = c.getInt(1) == 1,
                timezone = c.getString(2),
                rrule = c.getString(3),
            )
        }
    }

    /** Number of occurrences of [eventId] whose start is in [fromMillis, toExclusiveMillis). */
    private fun countInstancesBefore(
        eventId: Long,
        fromMillis: Long,
        toExclusiveMillis: Long,
    ): Int {
        if (toExclusiveMillis <= fromMillis) return 0
        // Query the Instances window ending one ms before the split so the box itself excludes it;
        // guard the BEGIN bound too in case the box is inclusive at either edge.
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, fromMillis)
        ContentUris.appendId(builder, toExclusiveMillis - 1)
        return safeQuery(
            builder.build(),
            arrayOf(CalendarContract.Instances.BEGIN),
            "${CalendarContract.Instances.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            var n = 0
            while (c.moveToNext()) {
                if (c.getLong(0) < toExclusiveMillis) n++
            }
            n
        } ?: 0
    }

    /**
     * Shrinks the master series so it ends just before [instanceStartMillis]. If the split is the
     * first occurrence (nothing precedes it), the master is deleted outright instead of being left
     * with an impossible UNTIL. Returns whether the original series was modified or removed.
     */
    private fun truncateSeries(
        eventId: Long,
        master: MasterEvent,
        instanceStartMillis: Long,
        occurrencesBefore: Int,
    ): Boolean {
        if (occurrencesBefore <= 0) {
            return safeDelete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                null,
                null,
            ) > 0
        }
        val truncated = RecurrenceRules.truncateBefore(
            master.rrule,
            Instant.ofEpochMilli(instanceStartMillis),
            master.allDay,
        ) ?: master.rrule
        val values = ContentValues().apply {
            put(CalendarContract.Events.RRULE, truncated)
        }
        return safeUpdate(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            values,
            null,
            null,
        ) > 0
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
            // Preserve the original rule verbatim when supplied (keeps BYDAY/INTERVAL/UNTIL from
            // CalDAV events intact); otherwise derive a simple rule from the chosen frequency.
            put(CalendarContract.Events.RRULE, input.rrule ?: "FREQ=${input.frequency.name}")
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
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.RRULE,
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
                val displayColor = c.getInt(9)
                val calendarColor = c.getInt(10)
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
                    color = if (displayColor != 0) displayColor else calendarColor,
                    rrule = c.getString(11),
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
        try {
            resolver.registerContentObserver(uri, /* notifyForDescendants = */ true, observer)
        } catch (_: SecurityException) {
            // Permission was revoked between the gate check and here; emit nothing and close.
        }
        awaitClose {
            try {
                resolver.unregisterContentObserver(observer)
            } catch (_: SecurityException) {
                // no-op
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val LOCAL_ACCOUNT_NAME = "Calendarium"
    }
}
