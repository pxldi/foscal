package app.foscal.core.model

/**
 * The unit a reminder offset is expressed in.
 *
 * Ordered ascending by [minutes]; [ReminderDuration.split] relies on that to find the coarsest unit
 * a value divides into exactly.
 */
enum class ReminderUnit(val minutes: Int, val singular: String) {
    MINUTES(1, "minute"),
    HOURS(60, "hour"),
    DAYS(1440, "day"),
    WEEKS(10_080, "week"),
    ;

    /** "minutes" / "1 minute" — the label the picker shows next to the amount. */
    fun label(count: Int): String = if (count == 1) singular else "${singular}s"
}

/**
 * Conversion and formatting for reminder offsets, in minutes before the event starts.
 *
 * Pure so the custom-duration picker's arithmetic can be tested without a device. The provider
 * stores a plain minute count, so every unit the UI offers has to survive a round trip through one.
 */
object ReminderDuration {

    /**
     * The largest offset the picker accepts.
     *
     * Four weeks, not an arbitrary ceiling: the scheduler arms alarms for a window of
     * `7 days + the largest offset in use` (see [ReminderTrigger.horizonEnd]), so every extra day
     * allowed here is a day of extra provider reading on every sync. Four weeks covers the real use
     * — annual renewals, visa deadlines — without letting one stray reminder make every sync scan a
     * year of calendar.
     */
    const val MAX_MINUTES = 40_320

    /**
     * [value] of [unit] as a minute count, or null when the result is negative or past
     * [MAX_MINUTES]. Returning null rather than clamping keeps the picker honest: silently turning
     * "6 weeks" into "4 weeks" would be a reminder the user did not ask for.
     */
    fun toMinutes(value: Int, unit: ReminderUnit): Int? {
        if (value < 0) return null
        // Widened deliberately: 40320 weeks overflows Int, and the check below must see the real
        // product rather than whatever it wrapped to.
        val total = value.toLong() * unit.minutes
        return if (total > MAX_MINUTES) null else total.toInt()
    }

    /**
     * Splits [minutes] into the coarsest unit that divides it exactly, so re-opening the picker on
     * an existing 120-minute reminder shows "2 hours" rather than "120 minutes".
     */
    fun split(minutes: Int): Pair<Int, ReminderUnit> {
        if (minutes <= 0) return 0 to ReminderUnit.MINUTES
        val unit = ReminderUnit.entries.last { minutes % it.minutes == 0 }
        return minutes / unit.minutes to unit
    }

    /**
     * Chip and list label for an offset.
     *
     * Non-positive offsets all read "At start": the provider uses 0 for that, and a negative value
     * is sentinel noise — `Reminders.MINUTES_DEFAULT` is -1, meaning "the calendar's own default"
     * rather than an offset — which must never render as "-1 min".
     */
    fun label(minutes: Int): String {
        if (minutes <= 0) return "At start"
        val (value, unit) = split(minutes)
        // "min" rather than "minutes": these are chips, and the short form is what every other
        // calendar app shows.
        if (unit == ReminderUnit.MINUTES) return "$value min"
        return "$value ${unit.label(value)}"
    }
}
