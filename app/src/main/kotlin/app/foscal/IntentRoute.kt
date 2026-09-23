package app.foscal

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.IntentCompat
import app.foscal.core.model.Ics

/**
 * Where the [Intent] that started the app should land.
 *
 * Parsing lives here rather than in [MainActivity] so it can be tested without an Activity, and
 * because the rules are fiddlier than they look: other apps address a day and an event through
 * the same `content://com.android.calendar` authority and differ only in one path segment, and
 * `ACTION_EDIT` means "edit this one" or "make a new one" depending on whether an id came with it.
 */
sealed interface IntentRoute {

    /** Nothing to do beyond opening the app where the user left it. */
    data object None : IntentRoute

    /** The quick-add sheet, from the widget's `+`. */
    data object QuickAdd : IntentRoute

    /** Show one event. `instanceStartMillis` picks the occurrence of a recurring one. */
    data class Event(val id: Long, val instanceStartMillis: Long) : IntentRoute

    /** Move the calendar to the day containing `millis`. */
    data class Day(val millis: Long) : IntentRoute

    /** Open an existing event in the editor. */
    data class EditEvent(val id: Long, val instanceStartMillis: Long) : IntentRoute

    /** Offer to import an `.ics` file another app handed over. `uri` is a content or file URI. */
    data class ImportIcs(val uri: String) : IntentRoute

    /** Open the editor on a new event, filled in with whatever the sender supplied. */
    data class NewEvent(
        val title: String = "",
        val location: String = "",
        val description: String = "",
        val startMillis: Long? = null,
        val endMillis: Long? = null,
        val allDay: Boolean = false,
    ) : IntentRoute
}

/**
 * What a sender put in the intent, lifted off the Android types so the rules below can be read —
 * and tested — without an [Intent] or a [Uri] anywhere near them.
 */
data class RouteRequest(
    val action: String?,
    /** The whole data URI, for the file cases; the calendar cases use [path] and [id] instead. */
    val uri: String? = null,
    val mimeType: String? = null,
    /** First path segment of a `content://com.android.calendar` URI; null for anything else. */
    val path: String? = null,
    /** Trailing id of that URI, null when it names a collection rather than a row. */
    val id: Long? = null,
    val beginMillis: Long? = null,
    val endMillis: Long? = null,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
)

/**
 * Reads [intent] as an [IntentRoute].
 *
 * The app's own extras win: a notification tap and the widget's `+` both arrive with an action
 * this function would otherwise have no opinion about.
 */
fun routeFor(intent: Intent?): IntentRoute {
    if (intent == null) return IntentRoute.None

    val ownEventId = intent.getLongExtra(MainActivity.EXTRA_OPEN_EVENT_ID, -1L)
    if (ownEventId > 0L) {
        return IntentRoute.Event(
            id = ownEventId,
            instanceStartMillis = intent.getLongExtra(MainActivity.EXTRA_OPEN_INSTANCE_START, 0L),
        )
    }
    if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, false)) return IntentRoute.QuickAdd

    val (path, id) = intent.data?.calendarSegment() ?: (null to null)
    // getStream() rather than data for ACTION_SEND: a share sheet puts the file on EXTRA_STREAM
    // and leaves the data URI empty.
    val stream = if (intent.action == Intent.ACTION_SEND) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        null
    }
    return routeFor(
        RouteRequest(
            action = intent.action,
            uri = (stream ?: intent.data)?.toString(),
            mimeType = intent.type,
            path = path,
            id = id,
            beginMillis = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
                .takeIf { it > 0L },
            endMillis = intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L)
                .takeIf { it > 0L },
            title = intent.getStringExtra(CalendarContract.Events.TITLE).orEmpty(),
            location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION).orEmpty(),
            description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION).orEmpty(),
            allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false),
        ),
    )
}

/** The rules themselves, over plain data. */
fun routeFor(request: RouteRequest): IntentRoute {
    val eventId = request.id?.takeIf { request.path == PATH_EVENTS }

    // A calendar file, whichever way it arrived. Checked before the actions below because a file
    // manager and a browser both send ACTION_VIEW for one, and the calendar rules would see an
    // action they recognise on a URI they do not.
    if (request.isCalendarFile()) return IntentRoute.ImportIcs(request.uri!!)

    return when (request.action) {
        // The documented way to say "add an event". Google Calendar has always accepted EDIT for
        // this too, and enough senders learnt that habit that refusing it would look like a bug.
        Intent.ACTION_INSERT -> request.newEvent()

        Intent.ACTION_EDIT ->
            if (eventId != null) {
                IntentRoute.EditEvent(eventId, request.beginMillis ?: 0L)
            } else {
                request.newEvent()
            }

        Intent.ACTION_VIEW -> when {
            eventId != null -> IntentRoute.Event(eventId, request.beginMillis ?: 0L)
            // `/time` with no id means today, which is where the app opens anyway.
            request.path == PATH_TIME -> request.id?.let(IntentRoute::Day) ?: IntentRoute.None
            else -> IntentRoute.None
        }

        else -> IntentRoute.None
    }
}

/**
 * Whether this is somebody handing over an `.ics` file.
 *
 * The extension is checked as well as the type because `.ics` files are routinely served with a
 * generic one — plenty of providers report `application/octet-stream` for anything they do not
 * recognise — and a type check alone would turn the app away from the very file it can read. The
 * type alone is enough in the other direction: a share sheet often supplies no filename at all.
 */
private fun RouteRequest.isCalendarFile(): Boolean {
    if (uri.isNullOrEmpty()) return false
    if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return false
    // The import reads through ContentResolver, which cannot open a web address, so offering one
    // would only end in a failed import.
    if (uri.substringBefore(':').lowercase() !in FILE_SCHEMES) return false
    if (mimeType in CALENDAR_MIME_TYPES) return true
    return uri.substringBefore('?').endsWith(".ics", ignoreCase = true)
}

private val CALENDAR_MIME_TYPES = setOf(Ics.MIME_TYPE, "text/x-vcalendar")
private val FILE_SCHEMES = setOf("content", "file")

private const val PATH_EVENTS = "events"
private const val PATH_TIME = "time"

/**
 * The first path segment and trailing id of a `content://com.android.calendar/...` URI, or nulls
 * for anything else.
 */
private fun Uri.calendarSegment(): Pair<String?, Long?> {
    if (scheme != "content" || authority != CalendarContract.AUTHORITY) return null to null
    val segments = pathSegments
    if (segments.isEmpty()) return null to null
    return segments[0] to segments.getOrNull(1)?.toLongOrNull()
}

private fun RouteRequest.newEvent() = IntentRoute.NewEvent(
    title = title,
    location = location,
    description = description,
    startMillis = beginMillis,
    endMillis = endMillis,
    allDay = allDay,
)
