package app.foscal.ics

import android.content.Context
import android.net.Uri
import app.foscal.core.data.CalendarRepository
import app.foscal.core.model.Attendee
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.Ics
import app.foscal.core.model.IcsEvent
import app.foscal.core.model.RecurrenceRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How an import went: [imported] events were written, [skipped] could not be. */
data class ImportSummary(val imported: Int, val skipped: Int)

/**
 * Reads and writes `.ics` files through Storage Access Framework URIs.
 *
 * The app never touches the file system directly — the user picks a document and the resulting
 * URI is the only thing handed here, so no storage permission is needed and nothing is written
 * anywhere the user did not choose.
 */
@Singleton
class IcsTransfer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CalendarRepository,
) {

    /**
     * Writes every event on [calendarIds] to [target]. Returns how many events were written,
     * counting a recurring series and each of its individually edited occurrences separately —
     * they are separate VEVENTs in the file.
     */
    suspend fun export(target: Uri, calendarIds: Set<Long>): Int = withContext(Dispatchers.IO) {
        val exports = repository.getEventsForExport(calendarIds)
        val ids = exports.flatMap { export ->
            listOf(export.event.id) + export.overrides.map { it.event.id }
        }
        val reminders = repository.getReminderMinutesFor(ids)
        val attendees = repository.getAttendeesFor(ids)
        val events = exports.flatMap { it.toIcsEvents(reminders, attendees) }
        val text = Ics.write(events)
        // "wt" truncates. Plain "w" leaves any bytes past the new content in place, so exporting a
        // smaller calendar over an existing file would leave a tail of the previous export behind
        // and produce a file with two END:VCALENDAR lines.
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("Could not open $target for writing")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        events.size
    }

    /**
     * Reads [source] and creates every event it contains on [calendarId].
     *
     * A VEVENT carrying a RECURRENCE-ID replaces one occurrence of the series with the same UID, so
     * it can only be written once that series exists: masters are created first and the overrides
     * are then applied against the ids they produced. An override whose master is not in the file
     * is created as a standalone event instead of being dropped — the occurrence it describes is
     * real, and there is nothing here for it to override.
     *
     * An exception insert needs the master's `_sync_id`, which only exists straight away on local
     * calendars (`createEvent` mints it there). Importing a series with overrides into a CalDAV
     * calendar before its sync adapter has assigned one leaves those overrides in [ImportSummary
     * .skipped] rather than writing them somewhere they would duplicate the series.
     */
    suspend fun import(source: Uri, calendarId: Long): ImportSummary =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(source)?.use { input ->
                input.readTextCapped(MAX_IMPORT_BYTES)
            } ?: throw IOException("Could not open $source for reading")

            val zone = ZoneId.systemDefault()
            writeImported(repository, Ics.read(text, zone), calendarId, zone)
        }

    /**
     * Reads the whole stream as UTF-8, refusing anything over [limit] bytes.
     *
     * A calendar file is parsed entirely in memory, and the URI comes from a picker the user drove,
     * so the size is not knowable in advance — without a cap, choosing a multi-gigabyte file by
     * mistake gets the process OOM-killed instead of showing an error. `readNBytes` would express
     * this directly but is API 33 and minSdk here is 26.
     */
    private fun java.io.InputStream.readTextCapped(limit: Int): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("File is too large to import")
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

        /** Default document name offered in the save dialog. */
        fun defaultExportName(today: LocalDate = LocalDate.now()): String = "foscal-$today.ics"
    }
}

/**
 * Writes already-parsed [events] onto [calendarId]. Split out of [IcsTransfer.import] so the
 * ordering rules below can be tested without a `Context` or a document URI.
 */
internal suspend fun writeImported(
    repository: CalendarRepository,
    events: List<IcsEvent>,
    calendarId: Long,
    zone: ZoneId,
): ImportSummary {
    val (overrides, masters) = events.partition { it.isOverride }
    var imported = 0
    val masterIds = mutableMapOf<String, Long>()

    for (event in masters) {
        // A null id means the provider rejected the row (a read-only calendar, or a value it did
        // not like). One bad event must not abandon the rest of the file.
        val id = repository.createEvent(event.toEventInput(calendarId, zone)) ?: continue
        imported++
        event.uid?.let { masterIds[it] = id }
        for (exdate in event.exdates) {
            repository.deleteEventInstance(id, exdate.toEpochMilli())
        }
    }

    for (event in overrides) {
        val masterId = event.uid?.let { masterIds[it] }
        val written = if (masterId != null) {
            repository.updateEventInstance(
                masterId,
                event.recurrenceId!!.toEpochMilli(),
                event.toEventInput(calendarId, zone),
            )
        } else {
            repository.createEvent(event.toEventInput(calendarId, zone)) != null
        }
        if (written) imported++
    }

    return ImportSummary(imported = imported, skipped = events.size - imported)
}

internal fun Event.toIcsEvent(
    reminderMinutes: List<Int>,
    attendees: List<Attendee> = emptyList(),
): IcsEvent = IcsEvent(
    title = title,
    start = start,
    end = end,
    allDay = allDay,
    location = location,
    description = description,
    timezone = timezone,
    rrule = rrule,
    reminderMinutes = reminderMinutes,
    // The provider keeps the organizer in the same table as the guests, distinguished only by
    // RELATIONSHIP; RFC 5545 gives it its own property and forbids more than one, so the split
    // happens here rather than the writer emitting two ORGANIZER lines for a malformed row.
    organizer = attendees.firstOrNull { it.isOrganizer },
    attendees = attendees.filterNot { it.isOrganizer },
)

/**
 * The series and its overrides as VEVENTs, master first.
 *
 * All of them must carry the *master's* UID — that shared UID plus a RECURRENCE-ID is the only
 * thing tying an override back to the series it belongs to. The master's synthetic uid is
 * therefore computed once and copied down; letting the writer derive one per event would key each
 * override off its own (edited) title and start and orphan it.
 */
internal fun ExportEvent.toIcsEvents(
    reminders: Map<Long, List<Int>>,
    attendees: Map<Long, List<Attendee>> = emptyMap(),
): List<IcsEvent> {
    val master = event.toIcsEvent(
        reminders[event.id].orEmpty(),
        attendees[event.id].orEmpty(),
    ).copy(exdates = cancelledOccurrences.map(Instant::ofEpochMilli))
    val uid = master.uid ?: Ics.syntheticUid(master)
    return listOf(master.copy(uid = uid)) + overrides.map { override ->
        override.event.toIcsEvent(
            reminders[override.event.id].orEmpty(),
            attendees[override.event.id].orEmpty(),
        ).copy(
            uid = uid,
            // RFC 5545 §3.8.5.3: a VEVENT with a RECURRENCE-ID replaces one occurrence and must not
            // define a series of its own. AOSP leaves an exception row's RRULE null, but a sync
            // adapter is free to store one, and emitting it would turn the single occurrence this
            // VEVENT replaces into a second full series overlapping the first.
            rrule = null,
            recurrenceId = Instant.ofEpochMilli(override.originalInstanceTime),
            recurrenceIdAllDay = override.originalAllDay,
        )
    }
}

internal fun IcsEvent.toEventInput(calendarId: Long, zone: ZoneId): EventInput = EventInput(
    calendarId = calendarId,
    title = title,
    location = location,
    description = description,
    start = start,
    end = end,
    allDay = allDay,
    // All-day events are stored against UTC by contract; a timed event keeps the zone it was
    // authored in so importing does not re-anchor it, falling back to the device zone only when
    // the file carried a floating time.
    timezone = if (allDay) "UTC" else timezone ?: zone.id,
    // The provider writes RRULE only when the event is recurring, and it keys that off frequency.
    // A rule whose FREQ this app does not model (HOURLY, MINUTELY) parses to NONE, so the event
    // imports as a single occurrence rather than being dropped.
    frequency = RecurrenceRules.parse(rrule).frequency,
    rrule = rrule,
    reminderMinutes = reminderMinutes,
    // Null, not an empty list, when the file names nobody: writing a guest list is a
    // clear-and-reinsert, and importing an override onto an existing series would otherwise strip
    // the guests the series already has.
    attendees = (listOfNotNull(organizer) + attendees).takeIf { it.isNotEmpty() },
)
