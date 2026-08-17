package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderTriggerTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val utc = ZoneId.of("UTC")

    /** How the Calendar Provider stores an all-day event: midnight UTC on the day it falls. */
    private fun allDayStart(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun at(zone: ZoneId, text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // --- timed events -------------------------------------------------------

    @Test
    fun `timed reminder is a plain subtraction`() {
        val start = at(berlin, "2026-08-17T18:00")
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = false, minutesBefore = 15, zone = berlin)
        assertEquals(at(berlin, "2026-08-17T17:45"), trigger)
    }

    @Test
    fun `timed reminder ignores the zone entirely`() {
        val start = at(berlin, "2026-08-17T18:00")
        val fromBerlin = ReminderTrigger.triggerAtMillis(start, allDay = false, minutesBefore = 30, zone = berlin)
        val fromNewYork = ReminderTrigger.triggerAtMillis(start, allDay = false, minutesBefore = 30, zone = newYork)
        // A timed start is an absolute instant, so "30 minutes before" is the same moment worldwide.
        assertEquals(fromBerlin, fromNewYork)
    }

    @Test
    fun `zero offset on a timed event triggers at the start`() {
        val start = at(berlin, "2026-08-17T18:00")
        assertEquals(
            start,
            ReminderTrigger.triggerAtMillis(start, allDay = false, minutesBefore = 0, zone = berlin),
        )
    }

    // --- all-day events -----------------------------------------------------

    /**
     * The exact failure observed on the emulator: an all-day event on 18 Aug with a 15-minute
     * reminder armed for 01:45 on the 18th (UTC midnight = 02:00 CEST, minus 15) instead of 23:45
     * on the 17th.
     */
    @Test
    fun `all-day reminder anchors to local midnight east of UTC`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 15, zone = berlin)
        assertEquals(at(berlin, "2026-08-17T23:45"), trigger)
    }

    /**
     * West of UTC the same bug fired a day early rather than late: UTC midnight on the 18th is
     * 20:00 on the 17th in New York, so "15 minutes before" landed at 19:45 on the 17th.
     */
    @Test
    fun `all-day reminder anchors to local midnight west of UTC`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 15, zone = newYork)
        assertEquals(at(newYork, "2026-08-17T23:45"), trigger)
    }

    @Test
    fun `all-day reminder handles a half-hour offset zone`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 60, zone = kolkata)
        assertEquals(at(kolkata, "2026-08-17T23:00"), trigger)
    }

    @Test
    fun `all-day reminder in UTC is unchanged by the fix`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 15, zone = utc)
        assertEquals(start - 15 * 60_000L, trigger)
    }

    @Test
    fun `a one-day-before all-day reminder fires at local midnight the day before`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 1440, zone = berlin)
        assertEquals(at(berlin, "2026-08-17T00:00"), trigger)
    }

    @Test
    fun `all-day reminder is correct on the day a DST transition occurs`() {
        // Europe/Berlin leaves DST on 2026-10-25 at 03:00 -> 02:00. Midnight still exists, but the
        // day is 25 hours long, so a naive fixed-offset conversion drifts by an hour.
        val start = allDayStart(LocalDate.of(2026, 10, 25))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 30, zone = berlin)
        assertEquals(at(berlin, "2026-10-24T23:30"), trigger)
    }

    @Test
    fun `all-day reminder survives a zone whose midnight does not exist`() {
        // Chile skipped 2019-09-08 00:00 entirely: the day began at 01:00. atStartOfDay must resolve
        // forward to the first valid instant instead of throwing or silently landing the day before.
        val santiago = ZoneId.of("America/Santiago")
        val start = allDayStart(LocalDate.of(2019, 9, 8))
        val trigger = ReminderTrigger.triggerAtMillis(start, allDay = true, minutesBefore = 0, zone = santiago)
        val expected = LocalDate.of(2019, 9, 8).atStartOfDay(santiago).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
        assertEquals(1, LocalDate.of(2019, 9, 8).atStartOfDay(santiago).hour)
    }

    // --- horizon ------------------------------------------------------------

    @Test
    fun `horizon is the arming window plus a rounded-up lead`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = 15)
        assertEquals(at(berlin, "2026-08-26T00:00"), end.toEpochMilli())
    }

    @Test
    fun `horizon has no reminders at all`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = 0)
        assertEquals(at(berlin, "2026-08-25T00:00"), end.toEpochMilli())
    }

    /**
     * The case the emulator exposed twice over. A 14-day reminder on an event 20 days out has to be
     * armed today. `max(7, 14)` gives a 15-day window that stops short of the event on day 20, so
     * the reminder is silently never armed — which is exactly what the device showed. The window
     * has to reach the *event*, so the two spans add: 7 + 14 = 21 days.
     */
    @Test
    fun `horizon reaches an event beyond a two-week reminder lead`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = 20160)
        assertEquals(at(berlin, "2026-09-08T00:00"), end.toEpochMilli())
    }

    /**
     * Guards the specific regression: whatever the formula is, it must never return a window that
     * stops before an event whose reminder is due inside the arming window.
     */
    @Test
    fun `horizon covers every event whose reminder falls due within the arming window`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        for (offsetMinutes in listOf(0, 15, 1440, 10080, 20160, 43200)) {
            val end = ReminderTrigger.horizonEnd(now, berlin, offsetMinutes)
            // The last event that can produce a reminder due within the arming window.
            val latestRelevantEvent = now
                .plus(java.time.Duration.ofDays(ReminderTrigger.ARM_AHEAD_DAYS))
                .plus(java.time.Duration.ofMinutes(offsetMinutes.toLong()))
            assert(!end.isBefore(latestRelevantEvent)) {
                "offset $offsetMinutes: window ends $end, before $latestRelevantEvent"
            }
        }
    }

    @Test
    fun `horizon rounds a partial day of lead up`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        // 10 days and 1 minute of lead must add 11 days, not 10.
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = 14401)
        assertEquals(at(berlin, "2026-09-05T00:00"), end.toEpochMilli())
    }

    @Test
    fun `horizon treats a negative offset as none`() {
        val now = Instant.parse("2026-08-17T14:30:00Z")
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = -1)
        assertEquals(at(berlin, "2026-08-25T00:00"), end.toEpochMilli())
    }

    @Test
    fun `horizon is computed against the local date not the UTC one`() {
        // 23:30 UTC on the 17th is already the 18th in Berlin; the window must start from the day
        // the user is actually in.
        val now = Instant.parse("2026-08-17T23:30:00Z")
        val end = ReminderTrigger.horizonEnd(now, berlin, largestOffsetMinutes = 15)
        assertEquals(at(berlin, "2026-08-27T00:00"), end.toEpochMilli())
    }

    // --- ScheduledReminder.create ------------------------------------------

    @Test
    fun `create keeps the raw provider start while correcting the trigger`() {
        val start = allDayStart(LocalDate.of(2026, 8, 18))
        val reminder = ScheduledReminder.create(
            eventId = 2L,
            calendarId = 1L,
            title = "All-day holiday",
            location = null,
            startMillis = start,
            minutesBefore = 15,
            allDay = true,
            zone = berlin,
        )
        // startMillis is the alarm key and the Instances lookup value: it must stay untouched.
        assertEquals(start, reminder.startMillis)
        assertEquals(at(berlin, "2026-08-17T23:45"), reminder.triggerAtMillis)
    }
}
