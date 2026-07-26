package app.foscal.ics

import android.content.Context
import android.net.Uri
import app.foscal.core.data.CalendarRepository
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.Ics
import app.foscal.core.model.IcsEvent
import app.foscal.core.model.RecurrenceRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    /** Writes every event on [calendarIds] to [target]. Returns how many events were written. */
    suspend fun export(target: Uri, calendarIds: Set<Long>): Int = withContext(Dispatchers.IO) {
        val events = repository.getEventsForExport(calendarIds)
        val reminders = repository.getReminderMinutesFor(events.map { it.id })
        val text = Ics.write(events.map { it.toIcsEvent(reminders[it.id].orEmpty()) })
        // "wt" truncates. Plain "w" leaves any bytes past the new content in place, so exporting a
        // smaller calendar over an existing file would leave a tail of the previous export behind
        // and produce a file with two END:VCALENDAR lines.
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: throw IOException("Could not open $target for writing")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        events.size
    }

    /** Reads [source] and creates every event it contains on [calendarId]. */
    suspend fun import(source: Uri, calendarId: Long): ImportSummary =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(source)?.use { input ->
                input.readTextCapped(MAX_IMPORT_BYTES)
            } ?: throw IOException("Could not open $source for reading")

            val zone = ZoneId.systemDefault()
            val events = Ics.read(text, zone)
            var imported = 0
            for (event in events) {
                // A null id means the provider rejected the row (a read-only calendar, or a value
                // it did not like). One bad event must not abandon the rest of the file.
                if (repository.createEvent(event.toEventInput(calendarId, zone)) != null) imported++
            }
            ImportSummary(imported = imported, skipped = events.size - imported)
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

internal fun Event.toIcsEvent(reminderMinutes: List<Int>): IcsEvent = IcsEvent(
    title = title,
    start = start,
    end = end,
    allDay = allDay,
    location = location,
    description = description,
    timezone = timezone,
    rrule = rrule,
    reminderMinutes = reminderMinutes,
)

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
)
