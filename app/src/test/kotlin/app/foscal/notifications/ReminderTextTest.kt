package app.foscal.notifications

import app.foscal.R
import app.foscal.ui.util.UiText
import app.foscal.ui.util.uiPlural
import app.foscal.ui.util.uiText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the receiver gets from the platform for an English locale; the platform is not here. */
private const val DatePattern = "EEE, MMM d"

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
    ): UiText? = reminderWhen(
        startMillis = millis(start),
        nowMillis = millis(now),
        allDay = allDay,
        use24Hour = use24Hour,
        zone = zone,
        locale = locale,
        datePattern = DatePattern,
    )

    private fun at(day: UiText, time: String): UiText =
        uiText(R.string.notification_day_at_time, day, time)

    private val today = uiText(R.string.notification_today)
    private val tomorrow = uiText(R.string.notification_tomorrow)
    private val yesterday = uiText(R.string.notification_yesterday)

    @Test
    fun `close to the event it says how long you have`() {
        assertEquals(uiPlural(R.plurals.notification_in_minutes, 15), label(now.plusMinutes(15)))
        assertEquals(uiPlural(R.plurals.notification_in_minutes, 59), label(now.plusMinutes(59)))
        assertEquals(uiText(R.string.notification_now), label(now))
        assertEquals(uiPlural(R.plurals.notification_minutes_ago, 5), label(now.minusMinutes(5)))
    }

    @Test
    fun `further out it says when the event is instead`() {
        // An hour is where "how long you have" stops being the useful half.
        assertEquals(at(today, "14:00"), label(now.with(LocalTime.of(14, 0))))
        assertEquals(at(tomorrow, "09:00"), label(now.plusDays(1).with(LocalTime.of(9, 0))))
        assertEquals(
            at(UiText.Raw("Friday"), "09:00"),
            label(now.plusDays(3).with(LocalTime.of(9, 0))),
        )
    }

    @Test
    fun `beyond a week the weekday alone no longer places it`() {
        assertEquals(
            at(UiText.Raw("Tue, Sept 1"), "09:00"),
            label(now.plusDays(14).with(LocalTime.of(9, 0))),
        )
    }

    @Test
    fun `a late alarm admits it rather than repeating its offset`() {
        assertEquals(at(yesterday, "09:00"), label(now.minusDays(1).with(LocalTime.of(9, 0))))
    }

    @Test
    fun `an all-day event has no clock time to show`() {
        assertEquals(today, label(now.with(LocalTime.MIDNIGHT), allDay = true))
        assertEquals(tomorrow, label(now.plusDays(1).with(LocalTime.MIDNIGHT), allDay = true))
        assertEquals(
            UiText.Raw("Tue, Sept 1"),
            label(LocalDate.of(2026, 9, 1).atStartOfDay(), allDay = true),
        )
    }

    @Test
    fun `twelve-hour clocks get twelve-hour times`() {
        assertEquals(
            at(tomorrow, "9:00 am"),
            label(now.plusDays(1).with(LocalTime.of(9, 0)), use24Hour = false),
        )
    }

    @Test
    fun `an event with no start has nothing to say`() {
        assertNull(reminderWhen(0L, millis(now), false, true, zone, locale, DatePattern))
    }
}
