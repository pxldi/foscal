package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Frequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Everything in the editor the user can change, and nothing it loads from elsewhere.
 *
 * The editor compares a draft against the one it loaded to know whether Back would lose anything,
 * and keeps it in the [SavedStateHandle] so that Android killing the process while the user is in
 * another app does not lose it either. Calendars, recent locations and the event's identity are
 * read again on restore; only the user's input has nowhere else to come from.
 */
internal data class EditorDraft(
    val title: String,
    val calendarId: Long?,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String,
    val description: String,
    val frequency: Frequency,
    val interval: Int,
    val recurrenceEndDate: LocalDate?,
    val recurrenceCount: Int?,
    val byWeekday: Set<DayOfWeek>,
    val recurrenceDirty: Boolean,
    val showCustomRecurrence: Boolean,
    val reminderMinutes: List<Int>,
    val remindersTouched: Boolean,
    val attendees: List<Attendee>,
    val guestDraft: String,
    val color: Int?,
) {
    /**
     * Whether this draft says something different from [other]. `recurrenceDirty` and
     * `remindersTouched` only record that a control was touched, and the custom-recurrence panel
     * is only open or shut, so none of them is a change to the event on its own.
     */
    fun differsFrom(other: EditorDraft): Boolean =
        copy(recurrenceDirty = false, showCustomRecurrence = false, remindersTouched = false) !=
            other.copy(recurrenceDirty = false, showCustomRecurrence = false, remindersTouched = false)

    /**
     * Written as primitives and string lists, which is all a saved-state Bundle holds without a
     * serialization library.
     */
    fun writeTo(handle: SavedStateHandle) {
        handle[KEY_PRESENT] = true
        handle["$P.title"] = title
        handle["$P.calendarId"] = calendarId ?: NONE_LONG
        handle["$P.allDay"] = allDay
        handle["$P.start"] = start.toString()
        handle["$P.end"] = end.toString()
        handle["$P.location"] = location
        handle["$P.description"] = description
        handle["$P.frequency"] = frequency.name
        handle["$P.interval"] = interval
        handle["$P.until"] = recurrenceEndDate?.toString().orEmpty()
        handle["$P.count"] = recurrenceCount ?: NONE_INT
        handle["$P.byWeekday"] = byWeekday.map { it.value }.sorted().toIntArray()
        handle["$P.recurrenceDirty"] = recurrenceDirty
        handle["$P.showCustomRecurrence"] = showCustomRecurrence
        handle["$P.reminders"] = reminderMinutes.toIntArray()
        handle["$P.remindersTouched"] = remindersTouched
        handle["$P.guestEmails"] = ArrayList(attendees.map { it.email })
        handle["$P.guestNames"] = ArrayList(attendees.map { it.name.orEmpty() })
        handle["$P.guestStatuses"] = ArrayList(attendees.map { it.status.name })
        handle["$P.guestOrganizer"] = attendees.map { it.isOrganizer }.toBooleanArray()
        handle["$P.guestOptional"] = attendees.map { it.optional }.toBooleanArray()
        handle["$P.guestDraft"] = guestDraft
        handle["$P.hasColor"] = color != null
        handle["$P.color"] = color ?: 0
    }

    companion object {
        private const val P = "editorDraft"
        private const val KEY_PRESENT = "$P.present"
        private const val NONE_LONG = -1L
        private const val NONE_INT = -1

        /** The draft saved in [handle], or null when there is none or it cannot be read. */
        fun readFrom(handle: SavedStateHandle): EditorDraft? {
            if (handle.get<Boolean>(KEY_PRESENT) != true) return null
            return runCatching {
                val emails = handle.get<ArrayList<String>>("$P.guestEmails").orEmpty()
                val names = handle.get<ArrayList<String>>("$P.guestNames").orEmpty()
                val statuses = handle.get<ArrayList<String>>("$P.guestStatuses").orEmpty()
                val organizer = handle.get<BooleanArray>("$P.guestOrganizer") ?: BooleanArray(0)
                val optional = handle.get<BooleanArray>("$P.guestOptional") ?: BooleanArray(0)
                EditorDraft(
                    title = handle.get<String>("$P.title")!!,
                    calendarId = handle.get<Long>("$P.calendarId")?.takeIf { it != NONE_LONG },
                    allDay = handle.get<Boolean>("$P.allDay")!!,
                    start = LocalDateTime.parse(handle.get<String>("$P.start")),
                    end = LocalDateTime.parse(handle.get<String>("$P.end")),
                    location = handle.get<String>("$P.location")!!,
                    description = handle.get<String>("$P.description")!!,
                    frequency = Frequency.valueOf(handle.get<String>("$P.frequency")!!),
                    interval = handle.get<Int>("$P.interval")!!,
                    recurrenceEndDate = handle.get<String>("$P.until")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(LocalDate::parse),
                    recurrenceCount = handle.get<Int>("$P.count")?.takeIf { it != NONE_INT },
                    byWeekday = handle.get<IntArray>("$P.byWeekday")!!.map(DayOfWeek::of).toSet(),
                    recurrenceDirty = handle.get<Boolean>("$P.recurrenceDirty")!!,
                    showCustomRecurrence = handle.get<Boolean>("$P.showCustomRecurrence")!!,
                    reminderMinutes = handle.get<IntArray>("$P.reminders")!!.toList(),
                    remindersTouched = handle.get<Boolean>("$P.remindersTouched")!!,
                    attendees = emails.indices.map { i ->
                        Attendee(
                            email = emails[i],
                            name = names.getOrNull(i)?.takeIf { it.isNotEmpty() },
                            status = AttendeeStatus.valueOf(statuses[i]),
                            isOrganizer = organizer.getOrElse(i) { false },
                            optional = optional.getOrElse(i) { false },
                        )
                    },
                    guestDraft = handle.get<String>("$P.guestDraft")!!,
                    color = handle.get<Int>("$P.color")?.takeIf { handle.get<Boolean>("$P.hasColor") == true },
                )
            }.getOrNull()
        }
    }
}

internal fun EditorUiState.toDraft(): EditorDraft = EditorDraft(
    title = title,
    calendarId = selectedCalendarId,
    allDay = allDay,
    start = startDate.atTime(startTime),
    end = endDate.atTime(endTime),
    location = location,
    description = description,
    frequency = frequency,
    interval = interval,
    recurrenceEndDate = recurrenceEndDate,
    recurrenceCount = recurrenceCount,
    byWeekday = byWeekday,
    recurrenceDirty = recurrenceDirty,
    showCustomRecurrence = showCustomRecurrence,
    reminderMinutes = reminderMinutes,
    remindersTouched = remindersTouched,
    attendees = attendees,
    guestDraft = guestDraft,
    color = color,
)

/** This state with the user's input from [draft] laid over what was loaded. */
internal fun EditorUiState.withDraft(draft: EditorDraft): EditorUiState = copy(
    title = draft.title,
    // A calendar hidden or removed since the draft was saved cannot be written to.
    selectedCalendarId = draft.calendarId?.takeIf { id -> availableCalendars.any { it.id == id } }
        ?: selectedCalendarId,
    allDay = draft.allDay,
    startDate = draft.start.toLocalDate(),
    startTime = draft.start.toLocalTime(),
    endDate = draft.end.toLocalDate(),
    endTime = draft.end.toLocalTime(),
    location = draft.location,
    description = draft.description,
    frequency = draft.frequency,
    interval = draft.interval,
    recurrenceEndDate = draft.recurrenceEndDate,
    recurrenceCount = draft.recurrenceCount,
    byWeekday = draft.byWeekday,
    recurrenceDirty = draft.recurrenceDirty,
    showCustomRecurrence = draft.showCustomRecurrence,
    reminderMinutes = draft.reminderMinutes,
    remindersTouched = draft.remindersTouched,
    attendees = draft.attendees,
    guestDraft = draft.guestDraft,
    color = draft.color,
)
