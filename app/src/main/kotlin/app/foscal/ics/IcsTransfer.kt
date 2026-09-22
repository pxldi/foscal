package app.foscal.ics

import android.content.Context
import android.net.Uri
import app.foscal.core.data.CalendarRepository
import app.foscal.core.model.Attendee
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.ExportEvent
import app.foscal.core.model.Ics
import app.foscal.core.model.IcsEvent
import app.foscal.core.model.RecurrenceRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How an import went: [imported] events were written, [skipped] could not be read or were refused,
 * and [duplicates] were already on the calendar from an earlier import.
 */
data class ImportSummary(val imported: Int, val skipped: Int, val duplicates: Int = 0)

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
        // Only the reminders Foscal would deliver itself. EMAIL and SMS rows belong to the server
        // that syncs them, and writing them as DISPLAY alarms turned them into notifications in
        // whatever app read the file.
        val reminders = repository.getReminderMinutesFor(ids, notifiableOnly = true)
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

    /** Reads [source] and creates every event it contains on [calendarId]; see [writeImported]. */
    suspend fun import(source: Uri, calendarId: Long): ImportSummary =
        withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            // The file is held in memory several times over while it is parsed, and the cap on its
            // size does not make that safe on a small heap. Running out is the user's file being
            // too big, not a crash.
            val document = try {
                val text = context.contentResolver.openInputStream(source)?.use { input ->
                    input.readTextCapped(MAX_IMPORT_BYTES)
                } ?: throw IOException("Could not open $source for reading")
                Ics.readDocument(text, zone)
            } catch (_: OutOfMemoryError) {
                throw IOException("File is too large to import")
            }
            // An unknown calendar takes the synced path, which never writes exception rows.
            val isLocal = repository.getCalendars().firstOrNull { it.id == calendarId }?.isLocal == true
            writeImported(repository, document.events, calendarId, zone, isLocal, document.rejected)
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
 * rules below can be tested without a `Context` or a document URI.
 *
 * - **Duplicates.** Every event is written with its UID, a synthetic one when the file has none.
 *   A series whose UID is already on the calendar is skipped with everything that belongs to it,
 *   so importing the same file twice adds nothing.
 * - **Cancelled occurrences** go into the master's `EXDATE` column as it is inserted. No
 *   exception row is written for them.
 * - **Overrides.** On a local calendar an override becomes an exception row against the master,
 *   which `createEvent` gave a `_sync_id`. On a synced calendar the master has no `_sync_id` until
 *   its adapter runs, and an exception inserted before then can wipe the series. There the
 *   occurrence is excluded from the master and the override is created as its own event.
 * - **THISANDFUTURE** overrides split the series through `updateEventFollowing`, latest first, so
 *   each split rebases the rule the next one truncates.
 * - **RDATEs** become one-off copies of the series, each with its own UID.
 * - An override whose master is not in the file is created as a standalone event: the occurrence
 *   it describes is real, and there is nothing here for it to override.
 *
 * [rejected] is the reader's count of VEVENTs it could not build, reported as skipped.
 */
internal suspend fun writeImported(
    repository: CalendarRepository,
    events: List<IcsEvent>,
    calendarId: Long,
    zone: ZoneId,
    isLocal: Boolean = true,
    rejected: Int = 0,
): ImportSummary {
    val (overrides, masters) = events.partition { it.isOverride }
    val uidOf = { event: IcsEvent -> event.uid ?: Ics.syntheticUid(event) }
    val masterUids = masters.mapNotNull { it.uid }.toSet()
    val overridesOf = overrides.filter { it.uid in masterUids }.groupBy { it.uid!! }
    val orphans = overrides.filter { it.uid !in masterUids }
    val existing = repository.findEventUids(
        calendarId,
        masters.map(uidOf) + orphans.map(::standaloneUid),
    )

    var imported = 0
    var failed = 0
    var duplicates = 0
    suspend fun create(input: EventInput) {
        if (repository.createEvent(input) != null) imported++ else failed++
    }

    for (master in masters) {
        val uid = uidOf(master)
        val own = master.uid?.let { overridesOf[it] }.orEmpty()
        if (uid in existing) {
            duplicates += 1 + own.size + master.rdates.size
            continue
        }
        val (following, single) = own.partition { it.thisAndFuture }
        val splits = following.sortedBy { it.recurrenceId }
        val firstSplit = splits.firstOrNull()?.recurrenceId
        // Occurrences past a split belong to the series the split creates, whose id is not known
        // here, so they cannot become exception rows. Like every override on a synced calendar,
        // they are excluded from their series and created as events of their own.
        val (exceptions, standalone) = if (isLocal) {
            single.partition { firstSplit == null || it.recurrenceId!! < firstSplit }
        } else {
            emptyList<IcsEvent>() to single
        }
        val replaced = standalone.map { it.recurrenceId!! }
        val exdates = master.exdates + replaced
        val input = master.toEventInput(calendarId, zone)
        val id = repository.createEvent(
            input.copy(uid = uid, exdates = exdates.takeIf { input.frequency != Frequency.NONE }),
        )
        if (id == null) {
            failed += 1 + own.size + master.rdates.size
            continue
        }
        imported++

        val length = Duration.between(master.start, master.end)
        for (rdate in master.rdates) {
            create(
                input.copy(
                    start = rdate,
                    end = rdate.plus(length),
                    frequency = Frequency.NONE,
                    rrule = null,
                    uid = "$uid-${rdate.toEpochMilli()}",
                ),
            )
        }
        for (override in exceptions) {
            val written = repository.updateEventInstance(
                id,
                override.recurrenceId!!.toEpochMilli(),
                override.toEventInput(calendarId, zone),
            )
            if (written) imported++ else failed++
        }
        for (override in standalone) {
            create(override.toEventInput(calendarId, zone).copy(uid = standaloneUid(override)))
        }
        for ((index, override) in splits.withIndex().reversed()) {
            val from = override.recurrenceId!!
            val until = splits.getOrNull(index + 1)?.recurrenceId
            // The new series runs from this split to the next one, shifted by however far the
            // override moved its first occurrence. Its exclusions are the master's in that range,
            // moved the same distance.
            val shift = Duration.between(from, override.start)
            val ownExdates = (master.exdates + replaced)
                .filter { it >= from && (until == null || it < until) }
                .map { it.plus(shift) }
            val written = repository.updateEventFollowing(
                id,
                from.toEpochMilli(),
                override.toEventInput(calendarId, zone).copy(
                    frequency = input.frequency,
                    rrule = input.rrule,
                    uid = standaloneUid(override),
                    exdates = ownExdates.takeIf { input.frequency != Frequency.NONE },
                ),
                rebaseCount = true,
            )
            if (written) imported++ else failed++
        }
    }

    for (override in orphans) {
        val uid = standaloneUid(override)
        if (uid in existing) duplicates++ else create(override.toEventInput(calendarId, zone).copy(uid = uid))
    }

    return ImportSummary(imported = imported, skipped = rejected + failed, duplicates = duplicates)
}

/**
 * The UID an override is stored under when it is written as an event of its own. It cannot share
 * the series' UID: a sync adapter uploads each event under its UID, and two events with one UID
 * collide on the server.
 */
private fun standaloneUid(override: IcsEvent): String =
    "${override.uid ?: Ics.syntheticUid(override)}-${override.recurrenceId?.toEpochMilli() ?: 0}"

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
    uid = uid,
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
    access = access,
    availability = availability,
)
