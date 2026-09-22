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
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
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
     * Renames and recolours a calendar this app owns.
     *
     * Local only, for the same reason as [deleteLocalCalendar]: the name and colour of a calendar
     * that syncs come from the account it came from, so a change here would be overwritten by the
     * next sync or pushed out as a change the user made everywhere. Returns false when
     * [calendarId] is not local, or is already gone.
     */
    suspend fun updateLocalCalendar(calendarId: Long, name: String, color: Int): Boolean

    /**
     * Answers an invitation on the user's own behalf.
     *
     * Writes the status onto the user's own `Attendees` row, without the sync-adapter flag, so the
     * provider marks the event dirty and whatever adapter owns the calendar picks the reply up.
     * Whether it reaches the organiser is that adapter's business, and so is when: an account that
     * replies by mail sends that mail on its own next sync, not when this returns. This app sends
     * nothing itself.
     *
     * Returns false when the event has no row for this calendar's owner — an event nobody invited
     * the user to has nothing to answer.
     */
    suspend fun setSelfAttendeeStatus(eventId: Long, status: AttendeeStatus): Boolean

    /**
     * The colour set on this one event, or null when it simply follows its calendar's.
     *
     * A separate read rather than another column on the shared projections: the editor is the only
     * caller that needs to tell "its own colour" from "the calendar's", because everywhere else
     * already gets the resolved answer from `DISPLAY_COLOR`.
     */
    suspend fun getEventColor(eventId: Long): Int?

    /** How many events sit on [calendarId]. Shown before offering to delete it. */
    suspend fun countEvents(calendarId: Long): Int

    /**
     * Removes a calendar this app owns, and every event on it.
     *
     * Only calendars on the app's own local account can go. A calendar that syncs belongs to the
     * account it came from: deleting it here would either be undone by the next sync or, worse,
     * pushed to the server as the user deleting it everywhere. Those are removed where they are
     * made. Returns false when [calendarId] is not local, or is already gone.
     */
    suspend fun deleteLocalCalendar(calendarId: Long): Boolean

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
     * Everyone on [eventId] — the organizer first, then the guests by name.
     *
     * The editor must load this before saving: [EventInput.attendees] replaces the whole list, so a
     * caller that saves without having read it first has to pass null and leave the guests alone.
     */
    suspend fun getAttendees(eventId: Long): List<Attendee>

    /** Attendees for many events at once, keyed by event id; events with none are absent. */
    suspend fun getAttendeesFor(eventIds: Collection<Long>): Map<Long, List<Attendee>>

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
     * Reminders to arm between [from] and [to], resolved in [zone], or **null** if any of the
     * calendar, instance, reminder or attendee reads failed. Cancelled occurrences and events the
     * user declined carry none.
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
     * Largest offset, in minutes, of any reminder this app would deliver itself, 0 if there are
     * none, or null if the provider could not be read. Drives how far ahead [getUpcomingReminders]
     * has to look; see `ReminderTrigger.horizonEnd`. The null matters as much as it does there: a
     * failure read as 0 shrinks the horizon, and every reminder beyond it gets cancelled.
     */
    suspend fun getLargestReminderOffsetMinutes(): Int?
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

    override suspend fun getEventColor(eventId: Long): Int? = withContext(Dispatchers.IO) {
        safeQuery(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.EVENT_COLOR),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0).takeIf { it != 0 } else null
        }
    }

    override suspend fun setSelfAttendeeStatus(eventId: Long, status: AttendeeStatus): Boolean =
        withContext(Dispatchers.IO) {
            val owner = selfAddressFor(eventId) ?: return@withContext false
            val rowId = selfAttendeeRowId(eventId, owner) ?: return@withContext false
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.ATTENDEE_STATUS, status.toProviderStatus())
            }
            val updated = safeUpdate(
                ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, rowId),
                values,
                null,
                null,
            )
            if (updated == 0) return@withContext false
            // `Events.SELF_ATTENDEE_STATUS` is deliberately left alone. The provider refuses to let
            // anyone but a sync adapter set it — "Updating selfAttendeeStatus in Events table is
            // not allowed", an IllegalArgumentException that safeUpdate would swallow whole — and
            // it does not need to be set: writing the attendee row makes the provider recompute
            // the column itself. It only manages that when the calendar's owner address matches
            // the attendee row exactly, case included, which is the one thing this app cannot
            // arrange; that is why nothing here reads the column back.
            true
        }

    /**
     * The id of the row [owner] holds on [eventId], or null when they hold none.
     *
     * Read and compared here rather than left to the provider as `attendeeEmail = ?`: SQLite's `=`
     * is case-sensitive, and an Exchange calendar routinely stores its owner address in one case
     * and the same person's attendee row in another. The screen decides whether to offer a reply
     * with [Attendee.normalizeAddress], so an SQL match found nothing on exactly the invitations
     * that looked answerable — buttons that did nothing, silently. Both sides now ask the same
     * question, and the update goes to a row by id rather than by a predicate.
     */
    private fun selfAttendeeRowId(eventId: Long, owner: String): Long? {
        val wanted = Attendee.normalizeAddress(owner)
        return safeQuery(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees._ID, CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val email = c.getString(1) ?: continue
                if (Attendee.normalizeAddress(email) == wanted) return@use c.getLong(0)
            }
            null
        }
    }

    /** The address the user is known by on the calendar [eventId] lives on. */
    private fun selfAddressFor(eventId: Long): String? {
        val calendarId = safeQuery(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        return safeQuery(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            arrayOf(
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.ACCOUNT_NAME,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            // OWNER_ACCOUNT is the address the server knows; ACCOUNT_NAME is the fallback for the
            // providers that leave it empty, where the two are the same thing anyway.
            c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun AttendeeStatus.toProviderStatus(): Int = when (this) {
        AttendeeStatus.ACCEPTED -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        AttendeeStatus.DECLINED -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        AttendeeStatus.TENTATIVE -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        AttendeeStatus.INVITED -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
    }

    override suspend fun updateLocalCalendar(calendarId: Long, name: String, color: Int): Boolean =
        withContext(Dispatchers.IO) {
            val account = localAccountOf(calendarId) ?: return@withContext false
            val values = ContentValues().apply {
                // Both, because they are two different things to the provider: NAME is the
                // calendar's identity to its sync adapter and DISPLAY_NAME is what gets shown.
                // A local calendar has no adapter to disagree with, and leaving NAME on the old
                // value would strand the rename anywhere the identity is what gets read.
                put(CalendarContract.Calendars.NAME, name)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
                put(CalendarContract.Calendars.CALENDAR_COLOR, color)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
                .buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
                .build()
            safeUpdate(uri, values, null, null) > 0
        }

    override suspend fun countEvents(calendarId: Long): Int = withContext(Dispatchers.IO) {
        safeQuery(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(calendarId.toString()),
            null,
        )?.use { it.count } ?: 0
    }

    override suspend fun deleteLocalCalendar(calendarId: Long): Boolean =
        withContext(Dispatchers.IO) {
            // Checked here rather than trusted from the caller: this is the one operation in the
            // app that destroys data it cannot put back, and a synced calendar reaching it would
            // be a deletion the user never asked for on every other device they own.
            val account = localAccountOf(calendarId) ?: return@withContext false
            // Without the sync-adapter flag the provider only marks the row deleted and waits for
            // an adapter to finish the job. Nothing syncs a local calendar, so it would sit there
            // as a tombstone for ever.
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
                .buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.ACCOUNT_TYPE_LOCAL,
                )
                .build()
            safeDelete(uri, null, null) > 0
        }

    /**
     * The account name of [calendarId] when it is a local calendar, null when it is not.
     *
     * Its own name, not this app's: the provider checks the account on the URI against the row,
     * and a local calendar some other app made — they do not all use the same name — would refuse
     * the delete if we insisted it was ours.
     */
    private fun localAccountOf(calendarId: Long): String? = safeQuery(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
        "${CalendarContract.Calendars._ID} = ?",
        arrayOf(calendarId.toString()),
        null,
    )?.use { c ->
        if (c.moveToFirst() && c.getString(1) == CalendarContract.ACCOUNT_TYPE_LOCAL) {
            c.getString(0)
        } else {
            null
        }
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
        setReminders(newId, input.reminderMinutes)
        writeAttendees(newId, input.attendees)
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
                writeAttendees(eventId, input.attendees)
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
            if (input.color != null) {
                put(CalendarContract.Events.EVENT_COLOR, input.color)
            } else {
                putNull(CalendarContract.Events.EVENT_COLOR)
            }
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
            // Attendees are seeded from the master the same way, but a null list here means the
            // caller has no guest list of its own — and for one occurrence of a series that is the
            // right answer: it keeps the series' guests rather than dropping them.
            writeAttendees(newId, input.attendees)
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
            ?: return@withContext false
        // The following series takes the user's edited values. When the recurrence rule was left
        // untouched, keep the original pattern but rebase a COUNT end so the series length is
        // preserved; when the user changed recurrence, apply their rule verbatim from [input].
        val followingRrule: String?
        val followingFrequency: Frequency
        if (rebaseCount) {
            followingFrequency = RecurrenceRules.parse(master.rrule).frequency
            followingRrule = RecurrenceRules.rebaseFollowing(master.rrule, occurrencesBefore)
            // A FREQ this app does not model (HOURLY) parses to NONE, and the provider would be
            // handed the new series as a one-off. Refusing leaves the series whole.
            if (followingFrequency == Frequency.NONE) return@withContext false
        } else {
            followingFrequency = input.frequency
            followingRrule = input.rrule
        }
        // A caller with no guest list of its own (a drag, or an event the user did not organize)
        // passes null. For a new series null would mean "nobody", so the master's list is copied.
        val attendees = input.attendees ?: readAttendees(eventId)
        // Create first, so a refused insert changes nothing. A refused truncate then takes the new
        // series back out rather than leaving the occurrences on the calendar twice.
        val newId = createEvent(
            input.copy(frequency = followingFrequency, rrule = followingRrule, attendees = attendees),
        ) ?: return@withContext false
        if (!truncateSeries(eventId, master, instanceStartMillis)) {
            safeDelete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, newId), null, null)
            return@withContext false
        }
        true
    }

    override suspend fun deleteEventFollowing(
        eventId: Long,
        instanceStartMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val master = loadMaster(eventId) ?: return@withContext false
        truncateSeries(eventId, master, instanceStartMillis)
    }

    /** Holds the recurrence-relevant columns of a master event (read from the Events table). */
    private data class MasterEvent(
        val dtStart: Long,
        val allDay: Boolean,
        val timezone: String?,
        val rrule: String?,
        val syncId: String?,
        val calendarId: Long,
        val duration: String?,
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
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DURATION,
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
                syncId = c.getString(4)?.takeIf { it.isNotBlank() },
                calendarId = c.getLong(5),
                duration = c.getString(6),
            )
        }
    }

    /**
     * Number of occurrences of [eventId] whose start is in [fromMillis, toExclusiveMillis), or
     * null if the provider could not be asked. Zero is a real answer that rebases a COUNT, so a
     * failed query must not look like it.
     *
     * Instances lists an overridden occurrence under the exception's own id and a cancelled one
     * not at all, so both are missing from this count and a COUNT rebased from it runs long by
     * that many. The rule itself would have to be expanded to do better.
     */
    private fun countInstancesBefore(
        eventId: Long,
        fromMillis: Long,
        toExclusiveMillis: Long,
    ): Int? {
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
        }
    }

    /**
     * Shrinks the master series so it ends just before [instanceStartMillis], and removes its
     * exceptions from that point on. If the split is the first occurrence, the master is deleted
     * outright instead of being left with an impossible UNTIL. Returns whether the original
     * series was modified or removed.
     *
     * "First" is read from DTSTART, not from how many instances precede the split: an earlier
     * occurrence that was edited or cancelled is not an instance of the master, and counting
     * none used to delete a series that still had past occurrences.
     */
    private fun truncateSeries(
        eventId: Long,
        master: MasterEvent,
        instanceStartMillis: Long,
    ): Boolean {
        val masterUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        if (instanceStartMillis <= master.dtStart) {
            return safeDelete(masterUri, null, null) > 0
        }
        val truncated = RecurrenceRules.truncateBefore(
            master.rrule,
            Instant.ofEpochMilli(instanceStartMillis),
            master.allDay,
        ) ?: return false
        // The provider rebuilds a series' Instances only when the update carries DTSTART, and it
        // decides from the update alone whether the event recurs. An RRULE on its own therefore
        // left the old expansion in place, every occurrence past the split still drawn, and DTSTART
        // without the RRULE re-expanded the series as a one-off. So the time columns go together.
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, master.dtStart)
            put(CalendarContract.Events.RRULE, truncated)
            put(CalendarContract.Events.DURATION, master.duration)
            put(CalendarContract.Events.EVENT_TIMEZONE, master.timezone)
            put(CalendarContract.Events.ALL_DAY, if (master.allDay) 1 else 0)
        }
        if (safeUpdate(masterUri, values, null, null) <= 0) return false
        deleteExceptionsFrom(eventId, master, instanceStartMillis)
        return true
    }

    /**
     * Deletes the exceptions of a series from [fromMillis] on. Once the series ends before them
     * they override nothing: a moved occurrence would stay on the calendar as a stray one-off
     * beside the new series, and a delete of "this and following" would leave it behind. This is
     * what Google Calendar does with later overrides on a split, too. An exception is linked by
     * `ORIGINAL_ID` locally and by `ORIGINAL_SYNC_ID` from a sync adapter, so both are matched. A
     * sync id is only unique within its calendar, hence the calendar check.
     */
    private fun deleteExceptionsFrom(masterId: Long, master: MasterEvent, fromMillis: Long) {
        val link = if (master.syncId != null) {
            "(${CalendarContract.Events.ORIGINAL_ID} = ? OR ${CalendarContract.Events.ORIGINAL_SYNC_ID} = ?)"
        } else {
            "${CalendarContract.Events.ORIGINAL_ID} = ?"
        }
        val args = listOfNotNull(
            masterId.toString(),
            master.syncId,
            master.calendarId.toString(),
            fromMillis.toString(),
        )
        safeDelete(
            CalendarContract.Events.CONTENT_URI,
            "$link AND ${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.ORIGINAL_INSTANCE_TIME} >= ?",
            args.toTypedArray(),
        )
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
        queryReminderMinutes(eventIds, notifiableOnly = false).orEmpty()
    }

    /**
     * Reminder offsets for many events in one pass, keyed by event id.
     *
     * See [readReminderMinutes] for what [notifiableOnly] excludes. Offsets below zero are dropped
     * either way: the provider uses `MINUTES_DEFAULT` (-1) for "whatever the calendar's default is",
     * and treating that as an offset would arm an alarm one minute *after* the event began.
     *
     * Null when any chunk could not be read. A partial map would drop the reminders of every event
     * in the failed chunk, and the scheduler cancels whatever is missing from its list.
     */
    private fun queryReminderMinutes(
        eventIds: Collection<Long>,
        notifiableOnly: Boolean,
    ): Map<Long, List<Int>>? {
        if (eventIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<Long, MutableList<Int>>()
        // SQLite caps a statement at 999 bound variables, so a large calendar has to be chunked
        // rather than passed as one IN clause.
        for (chunk in eventIds.distinct().chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = safeQuery(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(
                    CalendarContract.Reminders.EVENT_ID,
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD,
                ),
                "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            ) ?: return null
            cursor.use { c ->
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

    override suspend fun getAttendees(eventId: Long): List<Attendee> =
        withContext(Dispatchers.IO) { readAttendees(eventId) }

    private fun readAttendees(eventId: Long): List<Attendee> {
        val out = mutableListOf<Attendee>()
        safeQuery(
            CalendarContract.Attendees.CONTENT_URI,
            ATTENDEE_PROJECTION,
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += c.readAttendeeRow() ?: continue
            }
        }
        return out.sortAttendees()
    }

    override suspend fun getAttendeesFor(
        eventIds: Collection<Long>,
    ): Map<Long, List<Attendee>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()
        val out = mutableMapOf<Long, MutableList<Attendee>>()
        // Same 999-bound-variable cap as the reminder batch read; a large calendar has to be
        // chunked rather than passed as one IN clause.
        for (chunk in eventIds.distinct().chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            safeQuery(
                CalendarContract.Attendees.CONTENT_URI,
                ATTENDEE_PROJECTION + CalendarContract.Attendees.EVENT_ID,
                "${CalendarContract.Attendees.EVENT_ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val attendee = c.readAttendeeRow() ?: continue
                    out.getOrPut(c.getLong(ATTENDEE_PROJECTION.size)) { mutableListOf() } += attendee
                }
            }
        }
        out.mapValues { (_, attendees) -> attendees.sortAttendees() }
    }

    /**
     * Reads the [ATTENDEE_PROJECTION] row at the cursor, or null if it has no email.
     *
     * The provider will store a row with only a name — nothing enforces the column — but the
     * address is the identity everything downstream works from: RFC 5545 addresses an ATTENDEE by
     * its `mailto:` value, the guest rows open a `mailto:` intent, and the write path dedupes on
     * it. Such a row has nowhere to go, so it is dropped on the way in.
     */
    private fun android.database.Cursor.readAttendeeRow(): Attendee? {
        val email = getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Attendee(
            email = email,
            name = getString(0)?.trim()?.takeIf { it.isNotEmpty() },
            status = when (getInt(4)) {
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> AttendeeStatus.ACCEPTED
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> AttendeeStatus.DECLINED
                CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> AttendeeStatus.TENTATIVE
                else -> AttendeeStatus.INVITED
            },
            isOrganizer = getInt(2) == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
            optional = getInt(3) == CalendarContract.Attendees.TYPE_OPTIONAL,
        )
    }

    /** Organizer first, then guests by label — the provider returns rows in insertion order. */
    private fun List<Attendee>.sortAttendees(): List<Attendee> =
        distinctBy { it.email.lowercase() }
            .sortedWith(compareByDescending<Attendee> { it.isOrganizer }.thenBy { it.label.lowercase() })

    private fun deleteAttendees(eventId: Long) {
        safeDelete(
            CalendarContract.Attendees.CONTENT_URI,
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
        )
    }

    private fun setAttendees(eventId: Long, attendees: List<Attendee>) {
        attendees.distinctBy { it.email.lowercase() }.forEach { attendee ->
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.email)
                put(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
                put(
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    if (attendee.isOrganizer) {
                        CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                    } else {
                        CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                    },
                )
                put(
                    CalendarContract.Attendees.ATTENDEE_TYPE,
                    if (attendee.optional) {
                        CalendarContract.Attendees.TYPE_OPTIONAL
                    } else {
                        CalendarContract.Attendees.TYPE_REQUIRED
                    },
                )
                put(
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    when (attendee.status) {
                        AttendeeStatus.ACCEPTED -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
                        AttendeeStatus.DECLINED -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                        AttendeeStatus.TENTATIVE -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
                        AttendeeStatus.INVITED -> CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                    },
                )
            }
            safeInsert(CalendarContract.Attendees.CONTENT_URI, values)
        }
    }

    /**
     * Applies [attendees] to [eventId], or leaves the event's guest list alone when it is null.
     *
     * There is no partial update for the `Attendees` table, so a write is a clear-and-reinsert.
     * That is why null has to mean "untouched" rather than "none": every caller that does not model
     * guests would otherwise wipe a list its sync adapter owns.
     */
    private fun writeAttendees(eventId: Long, attendees: List<Attendee>?) {
        if (attendees == null) return
        deleteAttendees(eventId)
        setAttendees(eventId, attendees)
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
        // A failed read must not look like "no calendars"; see the interface KDoc. The same holds
        // for every read below: each one returns null rather than a shorter list.
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

        val events = queryReminderInstances(calendarIds, from, to) ?: return@withContext null
        if (events.isEmpty()) return@withContext emptyList()

        val eventIds = events.map { it.id }.toSet()
        // One batched Reminders query instead of one per event: a busy month easily produces
        // several hundred occurrences, and the per-event query made this an N+1 across a binder
        // boundary. Occurrences of a series share the master's reminder rows, so key on event id.
        val minutesByEvent = queryReminderMinutes(eventIds, notifiableOnly = true)
            ?: return@withContext null
        val attendeeRows = queryAttendeeStatuses(eventIds) ?: return@withContext null
        val declined = DeclinedEvents.find(
            rows = attendeeRows,
            calendarOfEvent = events.associate { it.id to it.calendarId },
            calendars = calendars,
        )

        val now = System.currentTimeMillis()
        events.filter { it.id !in declined }.flatMap { event ->
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

    /**
     * The occurrences between [from] and [to] that could carry a reminder, or null if the query
     * failed.
     *
     * Separate from [queryInstances] for two reasons. That reader skips untitled rows, which is a
     * display choice and would silently drop their reminders here. And it flattens a failed query
     * into an empty list, which the scheduler reads as "cancel everything".
     *
     * Cancelled occurrences are left out in the selection. A server marks a called-off meeting
     * `STATUS:CANCELLED` and keeps the row, so without this it would still notify.
     */
    private fun queryReminderInstances(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event>? {
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders)" +
            " AND (${CalendarContract.Instances.STATUS} IS NULL" +
            " OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.toEpochMilli())
        ContentUris.appendId(builder, to.toEpochMilli())
        val cursor = safeQuery(
            builder.build(),
            INSTANCE_PROJECTION,
            selection,
            calendarIds.map { it.toString() }.toTypedArray(),
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return null
        return cursor.use { c ->
            buildList { while (c.moveToNext()) add(c.readInstanceRow()) }
        }
    }

    /** Attendee rows for [eventIds], chunked like [queryReminderMinutes], or null on a failed read. */
    private fun queryAttendeeStatuses(eventIds: Collection<Long>): List<AttendeeStatusRow>? {
        val out = mutableListOf<AttendeeStatusRow>()
        for (chunk in eventIds.distinct().chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = safeQuery(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(
                    CalendarContract.Attendees.EVENT_ID,
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                ),
                "${CalendarContract.Attendees.EVENT_ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            ) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    out += AttendeeStatusRow(eventId = c.getLong(0), email = c.getString(1), status = c.getInt(2))
                }
            }
        }
        return out
    }

    override suspend fun getLargestReminderOffsetMinutes(): Int? = withContext(Dispatchers.IO) {
        val cursor = safeQuery(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            null,
            null,
            null,
        ) ?: return@withContext null
        var largest = 0
        cursor.use { c ->
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
        // Null rather than omitted: an event that had its own colour and has been put back on the
        // calendar's has to clear the column, and leaving it out would silently keep the old one.
        if (input.color != null) {
            put(CalendarContract.Events.EVENT_COLOR, input.color)
        } else {
            putNull(CalendarContract.Events.EVENT_COLOR)
        }

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

        /**
         * Column order `readAttendeeRow` depends on. The batch read appends `EVENT_ID` after
         * these, so the reader must never index past the end of this array.
         */
        private val ATTENDEE_PROJECTION = arrayOf(
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
            CalendarContract.Attendees.ATTENDEE_TYPE,
            CalendarContract.Attendees.ATTENDEE_STATUS,
        )

        /** Reminder methods Foscal delivers itself; EMAIL and SMS are the server's job. */
        private val NOTIFIABLE_REMINDER_METHODS = setOf(
            CalendarContract.Reminders.METHOD_DEFAULT,
            CalendarContract.Reminders.METHOD_ALERT,
            CalendarContract.Reminders.METHOD_ALARM,
        )
    }
}
