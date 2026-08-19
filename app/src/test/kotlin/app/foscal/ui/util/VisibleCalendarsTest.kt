package app.foscal.ui.util

import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleCalendarsTest {

    private val calendars = listOf(testCalendar(id = 1), testCalendar(id = 2), testCalendar(id = 3))

    @Test
    fun `month leaves out the calendars taken out of it`() = runTest {
        val ids = monthCalendarIds(
            FakeCalendarRepository(calendars = calendars),
            FakePreferences(monthHidden = setOf("2")),
        ).first()
        assertEquals(setOf(1L, 3L), ids)
    }

    // The month setting can only remove. Otherwise switching a calendar off everywhere and then
    // taking it out of the month would put it back into the month.
    @Test
    fun `a calendar hidden everywhere stays out of month`() = runTest {
        val ids = monthCalendarIds(
            FakeCalendarRepository(calendars = calendars),
            FakePreferences(hidden = setOf("1"), monthHidden = setOf("2")),
        ).first()
        assertEquals(setOf(3L), ids)
    }

    @Test
    fun `the other views are untouched by the month setting`() = runTest {
        val ids = visibleCalendarIds(
            FakeCalendarRepository(calendars = calendars),
            FakePreferences(monthHidden = setOf("2")),
        ).first()
        assertEquals(setOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `a calendar the provider marks invisible is out of both`() = runTest {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar(id = 1), testCalendar(id = 2, visible = false)),
        )
        assertEquals(setOf(1L), visibleCalendarIds(repo, FakePreferences()).first())
        assertEquals(setOf(1L), monthCalendarIds(repo, FakePreferences()).first())
    }

    @Test
    fun `a stored id that is not a number is ignored rather than crashing`() = runTest {
        val ids = monthCalendarIds(
            FakeCalendarRepository(calendars = calendars),
            FakePreferences(monthHidden = setOf("not-an-id", "3")),
        ).first()
        assertEquals(setOf(1L, 2L), ids)
    }
}
