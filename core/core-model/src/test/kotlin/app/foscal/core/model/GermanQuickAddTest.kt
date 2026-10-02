package app.foscal.core.model

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanQuickAddTest {

    // Wednesday 2026-07-08, as in QuickAddParserTest.
    private val today = LocalDate.of(2026, 7, 8)

    private fun parse(text: String, use24Hour: Boolean = true) =
        QuickAddParser.parse(text, today, use24Hour, Locale.GERMAN)

    @Test
    fun `weekday and time`() {
        val r = parse("Zahnarzt Freitag 9:30")
        assertEquals("Zahnarzt", r.title)
        assertEquals(LocalDate.of(2026, 7, 10), r.date)
        assertEquals(LocalTime.of(9, 30), r.time)
    }

    @Test
    fun `heute, morgen and übermorgen`() {
        assertEquals(today, parse("Einkaufen heute").date)
        assertEquals(today.plusDays(1), parse("Einkaufen morgen").date)
        assertEquals(today.plusDays(2), parse("Einkaufen übermorgen").date)
        assertEquals(today.plusDays(2), parse("Einkaufen Übermorgen").date)
        assertEquals("Einkaufen", parse("Einkaufen übermorgen").title)
    }

    @Test
    fun `morgen früh is tomorrow morning, am Morgen is not tomorrow`() {
        val r = parse("Joggen morgen früh")
        assertEquals(today.plusDays(1), r.date)
        assertEquals("Joggen", r.title)
        assertNull(parse("Joggen am Morgen").date)
    }

    @Test
    fun `hours with Uhr follow the clock setting`() {
        val r = parse("Meeting morgen 14 Uhr")
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(14, 0), r.time)
        assertEquals("Meeting", r.title)
        assertEquals(LocalTime.of(9, 30), parse("Call um 9:30 Uhr").time)
        assertEquals(LocalTime.of(9, 30), parse("Call 9.30 Uhr").time)
        assertEquals("Call", parse("Call um 9:30 Uhr").title)
        assertEquals(LocalTime.of(3, 0), parse("Call 3 Uhr").time)
        assertEquals(LocalTime.of(15, 0), parse("Call 3 Uhr", use24Hour = false).time)
    }

    @Test
    fun `a bare um reads like speech`() {
        assertEquals(LocalTime.of(15, 0), parse("Kaffee um 3").time)
        assertEquals(LocalTime.of(9, 0), parse("Kaffee um 9").time)
        assertEquals(LocalTime.of(19, 0), parse("Kaffee um 19").time)
        assertEquals(LocalTime.of(15, 0), parse("Kaffee um drei").time)
        assertEquals("Kaffee", parse("Kaffee um drei").title)
    }

    @Test
    fun `halb and viertel`() {
        assertEquals(LocalTime.of(14, 30), parse("Termin um halb drei").time)
        assertEquals(LocalTime.of(15, 15), parse("Termin viertel nach drei").time)
        assertEquals(LocalTime.of(14, 45), parse("Termin viertel vor drei").time)
        assertEquals(LocalTime.of(14, 45), parse("Termin dreiviertel drei").time)
        assertEquals(LocalTime.of(14, 15), parse("Termin viertel drei").time)
        assertEquals(LocalTime.of(12, 30), parse("Termin halb eins").time)
        assertEquals("Termin", parse("Termin um halb drei").title)
    }

    @Test
    fun `a day part settles the half of the day`() {
        assertEquals(LocalTime.of(6, 30), parse("Laufen halb sieben morgens").time)
        assertEquals(LocalTime.of(20, 0), parse("Essen um 8 abends").time)
        assertEquals(LocalTime.of(20, 0), parse("Essen acht Uhr abends").time)
        assertEquals(LocalTime.of(3, 0), parse("Flug um 3 nachts").time)
        assertEquals(LocalTime.of(23, 0), parse("Party um 11 nachts").time)
        assertEquals("Laufen", parse("Laufen halb sieben morgens").title)
    }

    @Test
    fun `mittag and mitternacht`() {
        assertEquals(LocalTime.of(12, 0), parse("Essen Mittag").time)
        assertEquals(LocalTime.of(0, 0), parse("Feuerwerk Mitternacht").time)
        assertNull(parse("Mittagessen").time)
    }

    @Test
    fun `nächsten and kommenden weekday skip today`() {
        assertEquals(LocalDate.of(2026, 7, 15), parse("Review nächsten Mittwoch").date)
        assertEquals(LocalDate.of(2026, 7, 13), parse("Review nächster Montag").date)
        assertEquals(LocalDate.of(2026, 7, 13), parse("Review kommenden Montag").date)
        assertEquals("Review", parse("Review nächsten Mittwoch").title)
    }

    @Test
    fun `a bare weekday is on or after today`() {
        assertEquals(today, parse("Abholen Mittwoch").date)
        assertEquals(LocalDate.of(2026, 7, 11), parse("Markt am Samstag").date)
        assertEquals(LocalDate.of(2026, 7, 11), parse("Markt Sonnabend").date)
        assertEquals(LocalDate.of(2026, 7, 12), parse("Brunch Sonntag").date)
        assertEquals("Markt", parse("Markt am Samstag").title)
    }

    @Test
    fun `montags is a habit, not a date`() {
        val r = parse("Yoga montags")
        assertNull(r.date)
        assertEquals("Yoga montags", r.title)
    }

    @Test
    fun `two-letter weekdays only where they cannot be words`() {
        assertEquals(LocalDate.of(2026, 7, 10), parse("Zahnarzt Fr 9:30").date)
        assertEquals(LocalDate.of(2026, 7, 10), parse("Zahnarzt Fr.").date)
        assertEquals(LocalDate.of(2026, 7, 12), parse("Brunch So").date)
        assertNull(parse("Treffen mit Fr. Müller").date)
        assertNull(parse("So ein Tag").date)
        assertNull(parse("Das mache ich so").date)
    }

    @Test
    fun `numeric dates, with and without a year`() {
        val r = parse("Konzert am 19.10.")
        assertEquals(LocalDate.of(2026, 10, 19), r.date)
        assertEquals("Konzert", r.title)
        assertEquals(LocalDate.of(2027, 10, 19), parse("Konzert 19.10.2027").date)
        assertEquals(LocalDate.of(2027, 10, 19), parse("Konzert 19.10.27").date)
        // Already past this year, so next year's.
        assertEquals(LocalDate.of(2027, 3, 1), parse("Steuer 1.3.").date)
    }

    @Test
    fun `dates with a month name`() {
        assertEquals(LocalDate.of(2026, 10, 19), parse("Konzert 19. Oktober").date)
        assertEquals(LocalDate.of(2026, 10, 19), parse("Konzert 19. Okt.").date)
        assertEquals(LocalDate.of(2027, 1, 5), parse("Urlaub 5. Jänner").date)
        assertEquals(LocalDate.of(2027, 3, 3), parse("Urlaub 3. März 2027").date)
        assertEquals("Urlaub", parse("Urlaub 3. März 2027").title)
    }

    @Test
    fun `a weekday in front of a date goes with it`() {
        val r = parse("Konzert Mo., 19.10. 20 Uhr")
        assertEquals(LocalDate.of(2026, 10, 19), r.date)
        assertEquals(LocalTime.of(20, 0), r.time)
        assertEquals("Konzert", r.title)
        assertEquals("Konzert", parse("Konzert am Freitag, den 23. Oktober").title)
    }

    @Test
    fun `a range of days is an all-day span`() {
        val r = parse("Urlaub vom 19. bis 23. Okt")
        assertEquals("Urlaub", r.title)
        assertEquals(LocalDate.of(2026, 10, 19), r.date)
        assertEquals(LocalDate.of(2026, 10, 23), r.endDate)
        assertTrue(r.allDay)
        assertNull(r.time)
        assertEquals(LocalDate.of(2026, 10, 23), parse("Messe 19.-23.10.").endDate)
        assertEquals(LocalDate.of(2026, 11, 2), parse("Reise 30.10. - 2.11.").endDate)
        assertEquals(LocalDate.of(2026, 10, 30), parse("Reise 30.10. - 2.11.").date)
    }

    @Test
    fun `a range across new year`() {
        val r = parse("Skiurlaub 28.12. bis 3.1.")
        assertEquals(LocalDate.of(2026, 12, 28), r.date)
        assertEquals(LocalDate.of(2027, 1, 3), r.endDate)
    }

    @Test
    fun `a backwards range is not a range`() {
        assertNull(parse("Messe 23.-19.10.").endDate)
    }

    @Test
    fun `a range of hours`() {
        val r = parse("Workshop morgen von 14 bis 16 Uhr")
        assertEquals("Workshop", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(14, 0), r.time)
        assertEquals(LocalTime.of(16, 0), r.endTime)
        assertFalse(r.allDay)
        assertEquals(LocalTime.of(11, 0), parse("Call 9:30-11 Uhr").endTime)
        assertEquals(LocalTime.of(15, 30), parse("Call 14:00–15:30").endTime)
    }

    @Test
    fun `ganztägig marks an all-day event`() {
        val r = parse("Konferenz morgen ganztägig")
        assertTrue(r.allDay)
        assertEquals(today.plusDays(1), r.date)
        assertEquals("Konferenz", r.title)
        assertTrue(parse("Messe den ganzen Tag").allDay)
    }

    @Test
    fun `English keeps working in German`() {
        val r = parse("Dentist friday 9:30am")
        assertEquals(LocalDate.of(2026, 7, 10), r.date)
        assertEquals(LocalTime.of(9, 30), r.time)
        assertEquals("Dentist", r.title)
        assertTrue(parse("PTO all day tomorrow").allDay)
    }

    @Test
    fun `am before a word is on, not the morning`() {
        val r = parse("2 Karten am Freitag")
        assertNull(r.time)
        assertEquals(LocalDate.of(2026, 7, 10), r.date)
        assertEquals("2 Karten", r.title)
        assertEquals(LocalTime.of(3, 0), parse("Flug 3am").time)
    }

    @Test
    fun `numbers that are not times stay in the title`() {
        assertEquals("2 Karten kaufen", parse("2 Karten kaufen").title)
        assertNull(parse("Preis um 20 Prozent senken").time)
        assertNull(parse("Termin um 25").time)
    }

    @Test
    fun `German is not read outside a German locale`() {
        val r = QuickAddParser.parse("Zahnarzt morgen 14 Uhr", today, locale = Locale.ENGLISH)
        assertNull(r.date)
        assertNull(r.time)
        assertEquals("Zahnarzt morgen 14 Uhr", r.title)
        assertEquals(today, QuickAddParser.parse("So wed", today, locale = Locale.ENGLISH).date)
    }

    @Test
    fun `impossible dates and every typed prefix leave the text alone without throwing`() {
        assertNull(parse("Termin 31.2.").date)
        assertNull(parse("Termin 12.13.").date)
        val typed = "Urlaub vom 19. bis 23. Okt 2026 von 14:30 bis 16 Uhr übermorgen"
        for (end in 1..typed.length) parse(typed.take(end))
    }
}
