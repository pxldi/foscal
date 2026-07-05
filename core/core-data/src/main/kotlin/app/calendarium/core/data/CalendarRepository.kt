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
}

@Singleton
class CalendarContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarRepository {

    private val resolver: ContentResolver get() = context.contentResolver

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
        resolver.query(
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

    override fun observeCalendars(): Flow<List<Calendar>> = contentChanges(
        uri = CalendarContract.Calendars.CONTENT_URI,
    ).onStart { emit(Unit) }.map { getCalendars() }.flowOn(Dispatchers.IO)

    override fun observeEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): Flow<List<Event>> = contentChanges(CalendarContract.Events.CONTENT_URI)
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
            resolver.insert(uri, values)?.let { ContentUris.parseId(it) }
        }

    override suspend fun setCalendarHidden(calendarId: Long, hidden: Boolean) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.VISIBLE, if (hidden) 0 else 1)
            }
            resolver.update(getCalendarUri(calendarId), values, null, null)
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
        resolver.query(
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
