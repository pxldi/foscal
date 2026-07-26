package app.foscal.ui.agenda

import app.foscal.at
import app.foscal.timedEvent
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agenda's render list is derived purely from its days, so it is tested directly rather than
 * through the view model's flows.
 */
class AgendaUiStateTest {

    private fun day(date: LocalDate) =
        AgendaDay(date, listOf(timedEvent(1, at(date, 9), at(date, 10))))

    private fun stateOf(vararg dates: LocalDate, today: LocalDate = dates.first()) =
        AgendaUiState(days = dates.map(::day), today = today)

    @Test
    fun `a month header is inserted before the first day of each month`() {
        val state = stateOf(
            LocalDate.of(2026, 7, 30),
            LocalDate.of(2026, 7, 31),
            LocalDate.of(2026, 8, 1),
        )
        assertEquals(
            listOf(
                "header 2026-07",
                "day 2026-07-30",
                "day 2026-07-31",
                "header 2026-08",
                "day 2026-08-01",
            ),
            state.items.map(::describe),
        )
    }

    @Test
    fun `a month that recurs a year later gets its own header`() {
        // Keying the header on the month alone would fold July 2027 into July 2026 and hide a
        // whole year's worth of scrolling from the only thing on screen naming the date.
        val state = stateOf(LocalDate.of(2026, 7, 1), LocalDate.of(2027, 7, 1))
        assertEquals(
            listOf(YearMonth.of(2026, 7), YearMonth.of(2027, 7)),
            state.items.filterIsInstance<AgendaItem.MonthHeader>().map { it.yearMonth },
        )
    }

    @Test
    fun `todayIndex points at today's row, not its month header`() {
        val today = LocalDate.of(2026, 7, 28)
        val state = stateOf(
            LocalDate.of(2026, 7, 26),
            today,
            LocalDate.of(2026, 7, 30),
            today = today,
        )
        assertEquals(AgendaItem.Day(day(today)), state.items[state.todayIndex])
    }

    @Test
    fun `todayIndex falls forward when today itself has no events`() {
        // An agenda skips empty days, so the day the user is looking for is often simply absent;
        // the next one that has not passed is the right place to land.
        val today = LocalDate.of(2026, 7, 28)
        val state = stateOf(
            LocalDate.of(2026, 7, 26),
            LocalDate.of(2026, 7, 30),
            today = today,
        )
        assertEquals("day 2026-07-30", describe(state.items[state.todayIndex]))
    }

    @Test
    fun `todayIndex lands on the last row when the whole agenda is in the past`() {
        val state = stateOf(
            LocalDate.of(2026, 7, 20),
            LocalDate.of(2026, 7, 21),
            today = LocalDate.of(2026, 8, 15),
        )
        assertEquals(state.items.lastIndex, state.todayIndex)
        assertEquals("day 2026-07-21", describe(state.items.last()))
    }

    @Test
    fun `an empty agenda has no items and no scroll target`() {
        val state = AgendaUiState()
        assertTrue(state.items.isEmpty())
        assertEquals(-1, state.todayIndex)
    }

    @Test
    fun `first and last date span the loaded days, ignoring headers`() {
        val state = stateOf(LocalDate.of(2026, 7, 30), LocalDate.of(2026, 8, 2))
        assertEquals(LocalDate.of(2026, 7, 30), state.firstDate)
        assertEquals(LocalDate.of(2026, 8, 2), state.lastDate)
    }

    private fun describe(item: AgendaItem): String = when (item) {
        is AgendaItem.MonthHeader -> "header ${item.yearMonth}"
        is AgendaItem.Day -> "day ${item.day.date}"
    }
}
