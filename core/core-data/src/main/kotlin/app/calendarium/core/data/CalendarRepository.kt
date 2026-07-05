package app.calendarium.core.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.CalendarContract
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads calendars and events from the Android system [CalendarContract] provider.
 *
 * On Android, the system calendar provider is the single source of truth that other apps
 * (such as DAVx5 for Nextcloud/ownCloud CalDAV, Google, Exchange, local calendars) write to.
 * Calendarium reads and writes through this contract, so any sync adapter the user installs
 * keeps everything in sync automatically.
 */
interface CalendarRepository {

    fun getCalendarUri(calendarId: Long): Uri

    suspend fun getCalendars(): List<Calendar>

    suspend fun getVisibleCalendars(): List<Calendar>

    suspend fun getEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event>
}

@Singleton
class CalendarContractRepository @Inject constructor(
    private val resolver: ContentResolver,
) : CalendarRepository {

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
            null,
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

    override suspend fun getVisibleCalendars(): List<Calendar> =
        getCalendars().filter { it.visible }

    override suspend fun getEvents(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<Event> = withContext(Dispatchers.IO) {
        if (calendarIds.isEmpty()) return@withContext emptyList()

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
        val uri = CalendarContract.Instances.CONTENT_URI
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${
            calendarIds.joinToString(",") { "?" }
        })"
        val args = calendarIds.map { it.toString() }.toTypedArray()
        val out = mutableListOf<Event>()
        resolver.query(
            ContentUris.appendId(
                ContentUris.appendId(uri.buildUpon(), from.toEpochMilli()),
                to.toEpochMilli(),
            ).build(),
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
        out
    }
}
