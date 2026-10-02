package app.foscal.ui.common

import app.foscal.core.data.CalendarRepository
import app.foscal.core.model.Event
import app.foscal.core.model.EventInput
import app.foscal.core.model.Frequency
import app.foscal.core.model.RecurrenceRules
import app.foscal.core.model.resolveEventTimezone
import app.foscal.ui.editor.RecurrenceScope
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Puts [event] down at a new time, at the breadth [scope] asks for, and says whether the provider
 * took it.
 *
 * Shared by the grid's drag, its TalkBack actions and the detail screen's Move, so the three cannot
 * disagree about what a move carries across. [scope] is only consulted for a series; a one-off has a
 * single meaning and is written straight through. Dragging one occurrence of a series used to
 * *always* mean "just this one", silently — the moved occurrence became an exception with no rule on
 * it, so the editor then showed it as repeating "Once" and it read as though the drag had deleted
 * the repeat.
 */
suspend fun CalendarRepository.moveEvent(
    event: Event,
    newStartMillis: Long,
    newEndMillis: Long,
    scope: RecurrenceScope,
    zone: ZoneId,
): Boolean {
    // Carry every reminder across the move; updateEvent rewrites the whole set, so dropping to just
    // the earliest one here would delete the rest.
    val reminders = getReminderMinutes(event.id).distinct().sorted()
    // The event's *own* colour, read rather than taken from Event.color: that field is the resolved
    // DISPLAY_COLOR and falls back to the calendar's, so writing it back would pin the calendar's
    // colour onto the event as if the user had chosen it. Omitting it was worse — EventInput.color
    // defaults to null and null means "clear the column", so every drag quietly stripped the colour
    // off whatever it moved.
    val color = getEventColor(event.id)
    val start = Instant.ofEpochMilli(newStartMillis)
    val end = Instant.ofEpochMilli(newEndMillis)
    val effective = if (event.isRecurring) scope else RecurrenceScope.SINGLE

    fun input(at: Instant, until: Instant, keepRule: Boolean) = EventInput(
        calendarId = event.calendarId,
        title = event.title,
        location = event.location,
        description = event.description,
        start = at,
        end = until,
        allDay = event.allDay,
        // "UTC", not ZoneOffset.UTC.id, which is "Z"; see Ics.UTC.
        timezone = if (event.allDay) "UTC" else resolveEventTimezone(event.timezone, zone),
        // Frequency is only a NONE/not-NONE switch once an explicit rule is supplied, but it has to
        // agree with the rule or the provider is handed DTEND and an RRULE at once, which it
        // rejects outright.
        frequency = if (keepRule) RecurrenceRules.parse(event.rrule).frequency else Frequency.NONE,
        rrule = if (keepRule) event.rrule else null,
        reminderMinutes = reminders,
        color = color,
    )

    return when {
        !event.isRecurring -> updateEvent(event.id, input(start, end, keepRule = false))

        effective == RecurrenceScope.SINGLE ->
            updateEventInstance(event.id, event.start.toEpochMilli(), input(start, end, keepRule = false))

        effective == RecurrenceScope.THIS_AND_FOLLOWING ->
            updateEventFollowing(
                event.id,
                event.start.toEpochMilli(),
                input(start, end, keepRule = true),
                // The rule itself was not edited, so the split series keeps the master's pattern
                // with its COUNT rebased to what is left of it.
                rebaseCount = true,
            )

        else -> {
            // The whole series shifts by however far this occurrence moved. Setting the master's
            // DTSTART to the dropped time instead would move the series to *this* occurrence's date,
            // which for anything past the first is a jump of however many repeats have already
            // happened.
            val master = getEventOccurrence(event.id, 0L)
            val delta = newStartMillis - event.start.toEpochMilli()
            master != null && updateEvent(
                event.id,
                input(master.start.plusMillis(delta), master.end.plusMillis(delta), keepRule = true),
            )
        }
    }
}

/**
 * Where [event] lands when it is put on [date], starting at [time], keeping its length.
 *
 * An all-day event ignores [time] and moves by whole UTC days, which is how the provider stores it;
 * converting it through [zone] would land it a day early anywhere west of UTC.
 */
fun movedTimes(event: Event, date: LocalDate, time: LocalTime, zone: ZoneId): Pair<Instant, Instant> {
    val start = if (event.allDay) {
        date.atStartOfDay(ZoneOffset.UTC).toInstant()
    } else {
        date.atTime(time).atZone(zone).toInstant()
    }
    return start to start.plusMillis(event.durationMillis)
}
