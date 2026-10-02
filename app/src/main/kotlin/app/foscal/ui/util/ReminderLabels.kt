package app.foscal.ui.util

import app.foscal.R
import app.foscal.core.model.ReminderDuration
import app.foscal.core.model.ReminderUnit

/**
 * Chip and list label for a reminder offset, in the coarsest unit it divides into: "5 min",
 * "2 hours", "1 week".
 *
 * Non-positive offsets all read "At start": the provider uses 0 for that, and a negative value is
 * sentinel noise — `Reminders.MINUTES_DEFAULT` is -1, meaning "the calendar's own default" rather
 * than an offset — which must never render as "-1 min".
 */
fun reminderLabel(minutes: Int): UiText {
    if (minutes <= 0) return uiText(R.string.reminder_at_start)
    val (value, unit) = ReminderDuration.split(minutes)
    // "min" rather than "minutes": these are chips, and the short form is what every other
    // calendar app shows.
    return when (unit) {
        ReminderUnit.MINUTES -> uiText(R.string.reminder_minutes_short, value)
        ReminderUnit.HOURS -> uiPlural(R.plurals.reminder_hours, value)
        ReminderUnit.DAYS -> uiPlural(R.plurals.reminder_days, value)
        ReminderUnit.WEEKS -> uiPlural(R.plurals.reminder_weeks, value)
    }
}

/** The unit's name as the duration picker shows it next to an amount of [count]: "minutes". */
fun reminderUnitName(unit: ReminderUnit, count: Int): UiText = uiPlural(
    when (unit) {
        ReminderUnit.MINUTES -> R.plurals.reminder_unit_minutes
        ReminderUnit.HOURS -> R.plurals.reminder_unit_hours
        ReminderUnit.DAYS -> R.plurals.reminder_unit_days
        ReminderUnit.WEEKS -> R.plurals.reminder_unit_weeks
    },
    count,
)
