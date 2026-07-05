package app.calendarium.ui.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {

    val weekHeaderFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())

    val fullWeekdayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    val dayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d", Locale.getDefault())

    val monthYearFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    val agendaDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun weekStartLabels(firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<String> {
        val order = (0..6).map { firstDayOfWeek.plus(it.toLong()) }
        return order.map { it.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }
    }

    fun startOfWeek(date: LocalDate, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
        val diff = (date.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    fun monthCells(month: YearMonth, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate?> {
        val firstOfMonth = month.atDay(1)
        val offset = (firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val daysInMonth = month.lengthOfMonth()
        val totalCells = ((offset + daysInMonth + 6) / 7) * 7
        return (0 until totalCells).map { i ->
            val day = i - offset + 1
            if (day in 1..daysInMonth) month.atDay(day) else null
        }
    }

    fun instantToLocal(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        LocalDateTime.ofInstant(instant, zone)
}
