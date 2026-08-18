package app.foscal.core.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.EventOverride
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.Frequency
import app.foscal.core.model.Ics
import app.foscal.core.model.RecurrenceRules
import app.foscal.core.model.ScheduledReminder
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
 * Foscal reads and writes through this contract, so any sync adapter the user
 * installs keeps everything in sync automatically — no CalDAV code in this app.
 */
interface CalendarRepository {

    fun getCalendarUri(calendarId: Long): Uri

    suspend fun getCalendars(): List<Calendar>

    suspend fun getEvents(calendarIds: Set<Long>, from: Instant, to: Instant): List<Event>

    /**
     * One event by id: the occurrence beginning at [instanceStartMillis] when that is a real
     * instance, otherwise the master row.
     *
     * Screens that open a single event (the editor, the detail screen) must not find it by scanning
     * a date window: every instance of a recurring series shares an id, so the only way to pick the
     * right one is the occurrence start the caller was given. Scanning also silently *fails* for
     * anything outside the window — an event years out loaded as a blank "new event" form.
     *
     * Falling back to the master row is what makes the lookup total. It is also the right answer
     * when there is no occurrence to ask for (opened from a notification, which carries only the
     * id) and when the occurrence has since been moved or cancelled.
     */
    suspend fun getEventOccurrence(eventId: Long, instanceStartMillis: Long = 0L): Event?

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

    /**
     * Returns this device's local (offline) calendar, creating it if it does not exist yet.
     * Idempotent: the Calendar Provider outlives the app's own data, so re-running onboarding
     * after a data clear or reinstall must adopt the existing calendar rather than add a second
     * one and strand the user's events in the first.
     */
    suspend fun ensureLocalCalendar(name: String, color: Int): Long?

    /**
     * Adds a new calendar on this device, returning its id.
     *
     * Local by necessity rather than by choice: a calendar that belongs to an account is created by
     * whatever syncs that account, and one this app invented on someone's Google or CalDAV account
     * would either be rejected or live only on the phone under a name that promises otherwise.
     *
     * Unlike [ensureLocalCalendar] this always adds one, because here a second calendar is exactly
     * what the user asked for.
     */
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

    /**
     * Reminder offsets for many events at once, keyed by event id. Events with no reminders are
     * absent from the map.
     *
     * Export would otherwise issue one query per event; on a calendar with a few thousand events
     * that is thousands of round-trips through the provider's binder interface.
     */
    suspend fun getReminderMinutesFor(eventIds: Collection<Long>): Map<Long, List<Int>>

    /**
     * Master event rows on [calendarIds] — the `Events` table, *not* the expanded `Instances` the
     * rest of the app reads — each with the recurrence exceptions that belong to it.
     *
     * Export must not go through instances: every occurrence of a recurring series is its own
     * instance row carrying the master's RRULE, so exporting them would write one VEVENT per
     * occurrence, each claiming to repeat forever. The master row is the single VEVENT the file
     * should contain.
     *
     * Recurrence exceptions are returned attached to their master rather than as top-level events:
     * on their own they look like ordinary one-off events, so exporting them flat would duplicate
     * an occurrence the series already covers. Which rows those are cannot be decided on
     * `ORIGINAL_ID` alone — a sync adapter links an exception by `ORIGINAL_SYNC_ID` — so both are
     * read and the sync id is resolved back to a local row id here.
     *
     * For recurring events the provider stores `DURATION` instead of `DTEND`, so [Event.end] is
     * derived from it here.
     */
    suspend fun getEventsForExport(calendarIds: Set<Long>): List<ExportEvent>

    /**
     * Reminders to arm between [from] and [to], resolved in [zone], or **null** if the calendar
     * provider could not be read at all.
     *
     * The null case matters: an empty list is an instruction to cancel every alarm, and a revoked
     * permission or a provider that is temporarily wedged used to be indistinguishable from
     * "the user has no reminders". That is how a transient failure permanently disarmed a user's
     * whole calendar until they next opened the app. Callers must treat null as "keep what is
     * already scheduled".
     */
    suspend fun getUpcomingReminders(
        from: Instant,
        to: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        excludedCalendarIds: Set<Long> = emptySet(),
    ): List<ScheduledReminder>?

    /**
     * Largest offset, in minutes, of any reminder this app would deliver itself, or 0 if there are
     * none. Drives how far ahead [getUpcomingReminders] has to look; see `ReminderTrigger.horizonEnd`.
     */
    suspend fun getLargestReminderOffsetMinutes(): Int
}

@Singleton
class CalendarContractRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    override suspend fun getCalendars(): List<Calendar> =
        withContext(Dispatchers.IO) { queryCalendars().orEmpty() }

    /**
     * Same read as [getCalendars] but returns null when the provider could not be queried, rather
     * than flattening that into an empty list. Only the reminder scheduler needs the distinction —
     * for UI callers an unreadable calendar list and an empty one look the same anyway.
     */
    private fun queryCalendars(): List<Calendar>? {
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
        val cursor = safeQuery(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        ) ?: return null
        cursor.use { c ->
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
        return out
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
                    "${CalendarContract.Events.EVENT_LOCATION} != '' AND " +
                    // Rows the user deleted linger until their sync adapter confirms the removal;
                    // suggesting locations from them keeps offering places already thrown away.
                    "${CalendarContract.Events.DELETED} != 1",
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

    override suspend fun ensureLocalCalendar(name: String, color: Int): Long? =
        withContext(Dispatchers.IO) {
            existingLocalCalendarId() ?: insertLocalCalendar(name, color)
        }

    override suspend fun createLocalCalendar(name: String, color: Int): Long? =
        withContext(Dispatchers.IO) { insertLocalCalendar(name, color) }

    /** Inserts a calendar on this app's own local account, whether or not one is already there. */
    private fun insertLocalCalendar(name: String, color: Int): Long? {
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
        return safeInsert(uri, values)?.let { ContentUris.parseId(it) }
    }

    /** Oldest calendar on our own local account, so repeated calls always resolve to the same one. */
    private fun existingLocalCalendarId(): Long? = safeQuery(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ?",
        arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_ACCOUNT_NAME),
        "${CalendarContract.Calendars._ID} ASC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

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
        setReminders(newId, input.reminderMinutes)
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
            put(CalendarContract.Events._SYNC_ID, "foscal-${java.util.UUID.randomUUID()}")
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
                deleteReminders(eventId)
                setReminders(eventId, input.reminderMinutes)
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
        if (newId > 0) {
            // The provider seeds the exception row by copying the master's children, reminders
            // included, so inserting the editor's set on top of that would leave the occurrence with
            // both. Reminders are all-or-nothing: clear first, then write the complete set.
            deleteReminders(newId)
            setReminders(newId, input.reminderMinutes)
        }
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
        createEvent(input.copy(frequency = followingFrequency, rrule = followingRrule)) != null
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
        withContext(Dispatchers.IO) { readReminderMinutes(eventId, notifiableOnly = false) }

    /**
     * Reminder offsets on [eventId], in minutes before start.
     *
     * [notifiableOnly] drops rows this app cannot honour. A CalDAV server can attach EMAIL and SMS
     * alarms to an event and DAVx⁵ syncs them down verbatim; posting a local notification for one
     * would duplicate a message the server already sends. METHOD_DEFAULT is included because the
     * provider uses it for "whatever the calendar's default is", which for us is an alert.
     */
    private fun readReminderMinutes(eventId: Long, notifiableOnly: Boolean): List<Int> {
        val out = mutableListOf<Int>()
        safeQuery(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (notifiableOnly && c.getInt(1) !in NOTIFIABLE_REMINDER_METHODS) continue
                out += c.getInt(0)
            }
        }
        return out
    }

    override suspend fun getReminderMinutesFor(
        eventIds: Collection<Long>,
    ): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        queryReminderMinutes(eventIds, notifiableOnly = false)
    }

    /**
     * Reminder offsets for many events in one pass, keyed by event id.
     *
     * See [readReminderMinutes] for what [notifiableOnly] excludes. Offsets below zero are dropped
     * either way: the provider uses `MINUTES_DEFAULT` (-1) for "whatever the calendar's default is",
     * and treating that as an offset would arm an alarm one minute *after* the event began.
     */
    private fun queryReminderMinutes(
        eventIds: Collection<Long>,
        notifiableOnly: Boolean,
    ): Map<Long, List<Int>> {
        if (eventIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, MutableList<Int>>()
        // SQLite caps a statement at 999 bound variables, so a large calendar has to be chunked
        // rather than passed as one IN clause.
        for (chunk in eventIds.distinct().chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            safeQuery(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(
                    CalendarContract.Reminders.EVENT_ID,
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD,
                ),
                "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (notifiableOnly && c.getInt(2) !in NOTIFIABLE_REMINDER_METHODS) continue
                    val minutes = c.getInt(1)
                    if (minutes < 0) continue
                    out.getOrPut(c.getLong(0)) { mutableListOf() } += minutes
                }
            }
        }
        return out.mapValues { (_, minutes) -> minutes.distinct().sorted() }
    }

    override suspend fun getEventsForExport(
        calendarIds: Set<Long>,
    ): List<ExportEvent> = withContext(Dispatchers.IO) {
        if (calendarIds.isEmpty()) return@withContext emptyList()
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Events.CALENDAR_ID} IN ($placeholders) AND " +
            "${CalendarContract.Events.DELETED} != 1"

        val masters = mutableListOf<Event>()
        val syncIdToMasterId = mutableMapOf<String, Long>()
        val exceptions = mutableListOf<ExceptionRow>()

        // Masters and exceptions come from one pass: splitting them into two queries would read the
        // same table twice and still need this join to be done in memory.
        safeQuery(
            CalendarContract.Events.CONTENT_URI,
            EXPORT_PROJECTION,
            selection,
            calendarIds.map { it.toString() }.toTypedArray(),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val originalId = c.getString(14)?.toLongOrNull()
                val originalSyncId = c.getString(15)?.takeIf { it.isNotBlank() }
                val originalStart = if (c.isNull(16)) null else c.getLong(16)
                if (originalStart != null && (originalId != null || originalSyncId != null)) {
                    exceptions += ExceptionRow(
                        masterId = originalId,
                        masterSyncId = originalSyncId,
                        originalInstanceTime = originalStart,
                        originalAllDay = c.getInt(17) == 1,
                        cancelled = !c.isNull(18) &&
                            c.getInt(18) == CalendarContract.Events.STATUS_CANCELED,
                        event = c.readEventRow(),
                    )
                    continue
                }
                val event = c.readEventRow() ?: continue
                if (event.title.isBlank()) continue
                masters += event
                c.getString(13)?.takeIf { it.isNotBlank() }?.let { syncIdToMasterId[it] = event.id }
            }
        }

        val overrides = mutableMapOf<Long, MutableList<EventOverride>>()
        val cancelled = mutableMapOf<Long, MutableList<Long>>()
        for (row in exceptions) {
            val masterId = row.masterId ?: row.masterSyncId?.let { syncIdToMasterId[it] } ?: continue
            if (row.cancelled) {
                cancelled.getOrPut(masterId) { mutableListOf() } += row.originalInstanceTime
            } else {
                val event = row.event ?: continue
                if (event.title.isBlank()) continue
                overrides.getOrPut(masterId) { mutableListOf() } += EventOverride(
                    originalInstanceTime = row.originalInstanceTime,
                    originalAllDay = row.originalAllDay,
                    event = event,
                )
            }
        }

        masters.map { master ->
            ExportEvent(
                event = master,
                overrides = overrides[master.id]
                    ?.sortedBy { it.originalInstanceTime }
                    .orEmpty(),
                cancelledOccurrences = cancelled[master.id]?.sorted().orEmpty(),
            )
        }
    }

    /** A raw recurrence-exception row, before its master has been resolved. */
    private class ExceptionRow(
        val masterId: Long?,
        val masterSyncId: String?,
        val originalInstanceTime: Long,
        val originalAllDay: Boolean,
        val cancelled: Boolean,
        val event: Event?,
    )

    override suspend fun getEventOccurrence(
        eventId: Long,
        instanceStartMillis: Long,
    ): Event? = withContext(Dispatchers.IO) {
        if (eventId <= 0L) return@withContext null
        instanceAt(eventId, instanceStartMillis) ?: masterEvent(eventId)
    }

    /**
     * The occurrence of [eventId] that begins exactly at [instanceStartMillis].
     *
     * The window is a single millisecond wide: the Instances box is inclusive at its edges, so a
     * wider one would also return the neighbouring occurrence of a frequent series.
     */
    private fun instanceAt(eventId: Long, instanceStartMillis: Long): Event? {
        if (instanceStartMillis <= 0L) return null
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, instanceStartMillis)
        ContentUris.appendId(builder, instanceStartMillis + 1)
        return safeQuery(
            builder.build(),
            INSTANCE_PROJECTION,
            "${CalendarContract.Instances.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.readInstanceRow()
                if (event.start.toEpochMilli() == instanceStartMillis) return@use event
            }
            null
        }
    }

    /** The master `Events` row for [eventId], read by id so it is found whatever its date. */
    private fun masterEvent(eventId: Long): Event? = safeQuery(
        CalendarContract.Events.CONTENT_URI,
        EVENT_PROJECTION,
        "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.DELETED} != 1",
        arrayOf(eventId.toString()),
        null,
    )?.use { c -> if (c.moveToFirst()) c.readEventRow() else null }

    override suspend fun getUpcomingReminders(
        from: Instant,
        to: Instant,
        zone: ZoneId,
        excludedCalendarIds: Set<Long>,
    ): List<ScheduledReminder>? = withContext(Dispatchers.IO) {
        // A failed read must not look like "no calendars"; see the interface KDoc.
        val calendars = queryCalendars() ?: return@withContext null
        // Hidden calendars are hidden everywhere else in the app, so notifying for them is a
        // reminder about an event the user cannot see. This is also the only lever a user has to
        // silence a noisy shared calendar without unsubscribing from it. Both switches count: the
        // provider's own VISIBLE flag (what other calendar apps and sync adapters set) and the
        // per-calendar toggle in Foscal's settings, which never touched the provider.
        val calendarIds = calendars
            .filter { it.visible && it.id !in excludedCalendarIds }
            .map { it.id }
            .toSet()
        if (calendarIds.isEmpty()) return@withContext emptyList()

        val events = getEvents(calendarIds, from, to)
        if (events.isEmpty()) return@withContext emptyList()

        // One batched Reminders query instead of one per event: a busy month easily produces
        // several hundred occurrences, and the per-event query made this an N+1 across a binder
        // boundary. Occurrences of a series share the master's reminder rows, so key on event id.
        val minutesByEvent = queryReminderMinutes(
            eventIds = events.map { it.id }.toSet(),
            notifiableOnly = true,
        )

        val now = System.currentTimeMillis()
        events.flatMap { event ->
            minutesByEvent[event.id].orEmpty().mapNotNull { minutes ->
                val reminder = ScheduledReminder.create(
                    eventId = event.id,
                    calendarId = event.calendarId,
                    title = event.title,
                    location = event.location,
                    startMillis = event.start.toEpochMilli(),
                    minutesBefore = minutes,
                    allDay = event.allDay,
                    zone = zone,
                )
                reminder.takeIf { it.triggerAtMillis > now }
            }
        }
    }

    override suspend fun getLargestReminderOffsetMinutes(): Int = withContext(Dispatchers.IO) {
        var largest = 0
        safeQuery(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) !in NOTIFIABLE_REMINDER_METHODS) continue
                largest = maxOf(largest, c.getInt(0))
            }
        }
        largest
    }

    private fun deleteReminders(eventId: Long) {
        safeDelete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
        )
    }

    private fun setReminders(eventId: Long, minutesBefore: List<Int>) {
        minutesBefore.distinct().forEach { minutes ->
            val values = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            safeInsert(CalendarContract.Reminders.CONTENT_URI, values)
        }
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
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)"
        val args = calendarIds.map { it.toString() }.toTypedArray()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.toEpochMilli())
        ContentUris.appendId(builder, to.toEpochMilli())
        val out = mutableListOf<Event>()
        safeQuery(
            builder.build(),
            INSTANCE_PROJECTION,
            selection,
            args,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.readInstanceRow()
                if (event.title.isBlank()) continue
                out += event
            }
        }
        return out
    }

    /** Reads the [INSTANCE_PROJECTION] row at the cursor's current position. */
    private fun android.database.Cursor.readInstanceRow(): Event {
        val displayColor = getInt(9)
        val calendarColor = getInt(10)
        return Event(
            id = getLong(0),
            calendarId = getLong(1),
            title = getString(2).orEmpty(),
            location = getString(3),
            description = getString(4),
            start = Instant.ofEpochMilli(getLong(5)),
            end = Instant.ofEpochMilli(getLong(6)),
            allDay = getInt(7) == 1,
            timezone = getString(8),
            color = if (displayColor != 0) displayColor else calendarColor,
            rrule = getString(11),
        )
    }

    /**
     * Reads the [EVENT_PROJECTION] row at the cursor's current position, or null if it carries no
     * start at all (nothing downstream can place such a row on a calendar).
     *
     * For a recurring event the provider stores `DURATION` *instead of* DTEND, so the end has to be
     * derived from it here — the Instances table is the only other place that expansion happens.
     */
    private fun android.database.Cursor.readEventRow(): Event? {
        if (isNull(5)) return null
        val start = getLong(5)
        val allDay = getInt(8) == 1
        val end = when {
            !isNull(6) -> getLong(6)
            else -> {
                val millis = getString(7)?.let { Ics.parseDuration(it) }
                start + (millis ?: if (allDay) 86_400_000L else 0L)
            }
        }
        val displayColor = getInt(10)
        val calendarColor = getInt(11)
        return Event(
            id = getLong(0),
            calendarId = getLong(1),
            title = getString(2).orEmpty(),
            location = getString(3),
            description = getString(4),
            start = Instant.ofEpochMilli(start),
            end = Instant.ofEpochMilli(end),
            allDay = allDay,
            timezone = getString(9),
            color = if (displayColor != 0) displayColor else calendarColor,
            rrule = getString(12),
        )
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
            // Permission was revoked between the gate check and here. Close rather than park
            // forever: the flow can never emit, and a live-but-silent collector would keep the
            // downstream flatMapLatest waiting on a signal that is not coming.
            close()
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
        private const val LOCAL_ACCOUNT_NAME = "Foscal"

        /** Column order both instance readers depend on; keep in sync with `readInstanceRow`. */
        private val INSTANCE_PROJECTION = arrayOf(
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

        /** Column order the master-row reader depends on; keep in sync with `readEventRow`. */
        private val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.CALENDAR_COLOR,
            CalendarContract.Events.RRULE,
        )

        /**
         * [EVENT_PROJECTION] plus the columns that describe how a row relates to a series. The
         * first 13 are byte-for-byte the same so `readEventRow` reads either projection; the
         * export-only columns are appended at 13..18.
         */
        private val EXPORT_PROJECTION = EVENT_PROJECTION + arrayOf(
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.ORIGINAL_ALL_DAY,
            CalendarContract.Events.STATUS,
        )

        /** Reminder methods Foscal delivers itself; EMAIL and SMS are the server's job. */
        private val NOTIFIABLE_REMINDER_METHODS = setOf(
            CalendarContract.Reminders.METHOD_DEFAULT,
            CalendarContract.Reminders.METHOD_ALERT,
            CalendarContract.Reminders.METHOD_ALARM,
        )
    }
}
