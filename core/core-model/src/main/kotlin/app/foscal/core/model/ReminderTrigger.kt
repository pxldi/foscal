package app.foscal.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Turns a provider event start plus a "minutes before" offset into the wall-clock instant an alarm
 * should fire, and works out how far ahead the scheduler has to look.
 *
 * Deliberately free of Android types so the arithmetic — the part that has actually been wrong in
 * production — can be tested exhaustively as plain JVM code.
 */
object ReminderTrigger {

    /**
     * How far ahead reminders are armed. A reminder due beyond this is left to a later sync, which
     * will happen many times before it comes due.
     */
    const val ARM_AHEAD_DAYS = 7L

    /**
     * When the alarm for [minutesBefore] on an occurrence starting at [startMillis] must fire.
     *
     * [startMillis] is the raw `Instances.BEGIN` value. For a timed event that is a true instant and
     * the answer is a plain subtraction. For an **all-day** event it is not: the provider stores
     * all-day events at *UTC* midnight, so subtracting directly anchors the reminder to midnight in
     * London rather than midnight where the user is. In UTC+2 a "15 minutes before" reminder on
     * tomorrow's all-day event fired at 01:45 tonight; in UTC-5 the same reminder fired at 18:45 on
     * the *previous* day. The correct anchor is local midnight of the day the event is displayed on,
     * which is what every other calendar app does and what the user means by "the day it starts".
     */
    fun triggerAtMillis(
        startMillis: Long,
        allDay: Boolean,
        minutesBefore: Int,
        zone: ZoneId,
    ): Long {
        val anchor = if (allDay) localMidnightOfAllDayStart(startMillis, zone) else startMillis
        return anchor - minutesBefore * 60_000L
    }

    /**
     * Local midnight of the calendar day an all-day occurrence falls on.
     *
     * The stored instant is read back in UTC to recover the *date* (reading it in the device zone
     * would shift it a day earlier anywhere west of UTC), then that date is re-anchored in [zone].
     * [ZoneId.getRules] resolves the gap correctly for the handful of zones where a DST transition
     * lands exactly on 00:00 — Lord Howe, and historically São Paulo — where midnight does not
     * exist and the day really starts at 01:00.
     */
    private fun localMidnightOfAllDayStart(startMillis: Long, zone: ZoneId): Long {
        val date: LocalDate = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * End of the **event** window the scheduler should query, starting at [now].
     *
     * The distinction between the two windows here is the whole point, and getting it wrong is
     * silent. Reminders are armed [ARM_AHEAD_DAYS] ahead of when they *fire*, but a reminder fires
     * at `eventStart - offset`, so the *events* that produce those reminders start up to
     * [largestOffsetMinutes] later than that. The window must therefore be the sum of the two, not
     * the larger of them.
     *
     * Concretely: a "2 weeks before" reminder on an event 20 days out has to be armed today. Taking
     * `max(7 days, 14 days)` gives a 15-day window that never returns that event, so the alarm is
     * never set — the same silent loss a bare 7-day window causes, just at a different distance.
     * `7 + 14 = 21` days reaches the event and arms the reminder.
     */
    fun horizonEnd(now: Instant, zone: ZoneId, largestOffsetMinutes: Int): Instant {
        val offsetMinutes = largestOffsetMinutes.toLong().coerceAtLeast(0L)
        // Ceiling division: Math.ceilDiv is Java 18+, and this module compiles against 17.
        val offsetDays = (offsetMinutes + MINUTES_PER_DAY - 1) / MINUTES_PER_DAY
        val days = ARM_AHEAD_DAYS + offsetDays
        // Rounded *up* to the following midnight. Snapping to a day boundary keeps the window
        // stable as the day passes, but truncating downwards would place the end up to 24h before
        // `now + days` — losing a reminder that is genuinely due inside the arming window.
        return now.atZone(zone).toLocalDate().plusDays(days + 1).atStartOfDay(zone).toInstant()
    }

    private const val MINUTES_PER_DAY = 1440L
}
