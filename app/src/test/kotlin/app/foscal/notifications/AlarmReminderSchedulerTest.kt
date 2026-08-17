package app.foscal.notifications

import app.foscal.core.model.ScheduledReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AlarmReminderSchedulerTest {

    private fun key(eventId: Long, startMillis: Long, minutes: Int) =
        AlarmReminderScheduler.alarmKey(eventId, startMillis, minutes)

    @Test
    fun `occurrences of one series get distinct alarm keys`() {
        val eventId = 42L
        val day = Duration.ofDays(1).toMillis()
        val start = 1_700_000_000_000L
        val keys = (0 until 30).map { key(eventId, start + it * day, 10) }

        assertEquals("every occurrence must map to its own alarm", 30, keys.toSet().size)
    }

    @Test
    fun `different reminder offsets on one occurrence get distinct keys`() {
        val start = 1_700_000_000_000L
        assertNotEquals(key(7L, start, 10), key(7L, start, 30))
    }

    @Test
    fun `different events at the same instant get distinct keys`() {
        val start = 1_700_000_000_000L
        assertNotEquals(key(7L, start, 10), key(8L, start, 10))
    }

    @Test
    fun `key is stable across calls`() {
        assertEquals(key(9L, 1_700_000_000_000L, 15), key(9L, 1_700_000_000_000L, 15))
    }

    @Test
    fun `trigger time is the occurrence start minus the offset`() {
        val start = 1_700_000_000_000L
        val reminder = reminder(eventId = 1L, start = start, minutes = 15)
        assertEquals(start - 15 * 60_000L, reminder.triggerAtMillis)
    }

    // --- selectAlarms -------------------------------------------------------

    private fun reminder(
        eventId: Long,
        start: Long,
        minutes: Int = 15,
        allDay: Boolean = false,
    ) = ScheduledReminder.create(
        eventId = eventId,
        calendarId = 1L,
        title = "Event $eventId",
        location = null,
        startMillis = start,
        minutesBefore = minutes,
        allDay = allDay,
        zone = ZoneOffset.UTC,
    )

    @Test
    fun `alarms already in the past are not armed`() {
        val now = 1_700_000_000_000L
        val selected = AlarmReminderScheduler.selectAlarms(
            listOf(
                reminder(1L, start = now - Duration.ofHours(1).toMillis()),
                reminder(2L, start = now + Duration.ofHours(1).toMillis()),
            ),
            nowMillis = now,
        )
        assertEquals(listOf(2L), selected.map { it.eventId })
    }

    @Test
    fun `alarms are armed soonest first`() {
        val now = 1_700_000_000_000L
        val hour = Duration.ofHours(1).toMillis()
        val selected = AlarmReminderScheduler.selectAlarms(
            listOf(
                reminder(3L, start = now + 3 * hour),
                reminder(1L, start = now + 1 * hour),
                reminder(2L, start = now + 2 * hour),
            ),
            nowMillis = now,
        )
        assertEquals(listOf(1L, 2L, 3L), selected.map { it.eventId })
    }

    /**
     * The per-app alarm cap has to cost us the *furthest* reminders, never nearer ones — dropping a
     * reminder due in an hour to keep one due in three weeks is the failure this ordering prevents.
     */
    @Test
    fun `the cap drops the most distant reminders`() {
        val now = 1_700_000_000_000L
        val hour = Duration.ofHours(1).toMillis()
        val all = (1L..10L).map { reminder(it, start = now + it * hour) }
        val selected = AlarmReminderScheduler.selectAlarms(all.reversed(), nowMillis = now, max = 4)

        assertEquals(4, selected.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), selected.map { it.eventId })
    }

    @Test
    fun `duplicate reminders collapse to one alarm`() {
        val now = 1_700_000_000_000L
        val start = now + Duration.ofHours(1).toMillis()
        val selected = AlarmReminderScheduler.selectAlarms(
            listOf(reminder(1L, start), reminder(1L, start), reminder(1L, start)),
            nowMillis = now,
        )
        // They share a request code, so arming all three would only overwrite the same alarm — but
        // the registry would then claim more alarms than exist.
        assertEquals(1, selected.size)
    }

    @Test
    fun `an all-day reminder is ordered by its corrected trigger`() {
        // Midnight UTC on day 2; in UTC+2 the 15-minute reminder is due the evening before, which
        // must sort it ahead of a timed event that starts earlier by raw start value.
        val berlin = ZoneId.of("Europe/Berlin")
        val allDay = ScheduledReminder.create(
            eventId = 1L,
            calendarId = 1L,
            title = "Holiday",
            location = null,
            startMillis = LocalDate.of(2026, 8, 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            minutesBefore = 15,
            allDay = true,
            zone = berlin,
        )
        val timed = ScheduledReminder.create(
            eventId = 2L,
            calendarId = 1L,
            title = "Late meeting",
            location = null,
            startMillis = LocalDateTime.parse("2026-08-18T01:00").atZone(berlin).toInstant().toEpochMilli(),
            minutesBefore = 0,
            allDay = false,
            zone = berlin,
        )
        val now = LocalDateTime.parse("2026-08-17T12:00").atZone(berlin).toInstant().toEpochMilli()
        val selected = AlarmReminderScheduler.selectAlarms(listOf(timed, allDay), nowMillis = now)

        assertEquals(listOf(1L, 2L), selected.map { it.eventId })
    }
}
