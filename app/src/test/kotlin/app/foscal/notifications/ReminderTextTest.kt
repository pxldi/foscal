package app.foscal.notifications

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderTextTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val locale: Locale = Locale.UK
    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 18, 11, 15)

    private fun millis(at: LocalDateTime): Long =
        at.atZone(zone).toInstant().toEpochMilli()

    private fun label(
        start: LocalDateTime,
        allDay: Boolean = false,
        use24Hour: Boolean = true,
    ): String? = reminderWhen(
        startMillis = millis(start),
        nowMillis = millis(now),
        allDay = allDay,
        use24Hour = use24Hour,
        zone = zone,
        locale = locale,
    )

    @Test
    fun `close to the event it says how long you have`() {
        assertEquals("In 15 min", label(now.plusMinutes(15)))
        assertEquals("In 59 min", label(now.plusMinutes(59)))
        assertEquals("Now", label(now))
        assertEquals("5 min ago", label(now.minusMinutes(5)))
    }

    @Test
    fun `further out it says when the event is instead`() {
        // An hour is where "how long you have" stops being the useful half.
        assertEquals("Today at 14:00", label(now.with(LocalTime.of(14, 0))))
        assertEquals("Tomorrow at 09:00", label(now.plusDays(1).with(LocalTime.of(9, 0))))
        assertEquals("Friday at 09:00", label(now.plusDays(3).with(LocalTime.of(9, 0))))
    }

    @Test
    fun `beyond a week the weekday alone no longer places it`() {
        assertEquals("Tue, Sept 1 at 09:00", label(now.plusDays(14).with(LocalTime.of(9, 0))))
    }

    @Test
    fun `a late alarm admits it rather than repeating its offset`() {
        assertEquals("Yesterday at 09:00", label(now.minusDays(1).with(LocalTime.of(9, 0))))
    }

    @Test
    fun `an all-day event has no clock time to show`() {
        assertEquals("Today", label(now.with(LocalTime.MIDNIGHT), allDay = true))
        assertEquals("Tomorrow", label(now.plusDays(1).with(LocalTime.MIDNIGHT), allDay = true))
        assertEquals(
            "Tue, Sept 1",
            label(LocalDate.of(2026, 9, 1).atStartOfDay(), allDay = true),
        )
    }

    @Test
    fun `twelve-hour clocks get twelve-hour times`() {
        assertEquals(
            "Tomorrow at 9:00 am",
            label(now.plusDays(1).with(LocalTime.of(9, 0)), use24Hour = false),
        )
    }

    @Test
    fun `an event with no start has nothing to say`() {
        assertNull(reminderWhen(0L, millis(now), false, true, zone, locale))
    }
}
