package app.foscal.ui.util

import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.testCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar(id = 1, visible = false), testCalendar(id = 2), testCalendar(id = 3)),
        )
        val ids = monthCalendarIds(repo, FakePreferences(monthHidden = setOf("2"))).first()
        assertEquals(setOf(3L), ids)
    }

    @Test
    fun `the other views are untouched by the month setting`() = runTest {
        val ids = visibleCalendarIds(FakeCalendarRepository(calendars = calendars)).first()
        assertEquals(setOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `a calendar the provider marks invisible is out of both`() = runTest {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar(id = 1), testCalendar(id = 2, visible = false)),
        )
        assertEquals(setOf(1L), visibleCalendarIds(repo).first())
        assertEquals(setOf(1L), monthCalendarIds(repo, FakePreferences()).first())
    }

    // The provider defaults SYNC_EVENTS to 0 and VISIBLE to 1, so "visible but not synced" is what
    // any app that forgets the column produces. AOSP Calendar and Etar leave those out.
    @Test
    fun `a visible calendar that is not synced is out`() = runTest {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar(id = 1), testCalendar(id = 2, syncEnabled = false)),
        )
        assertEquals(setOf(1L), visibleCalendarIds(repo).first())
    }

    @Test
    fun `a tick written by another app reaches the views`() = runTest {
        val repo = FakeCalendarRepository(calendars = calendars)
        repo.setCalendarVisible(2, false)
        assertEquals(setOf(1L, 3L), visibleCalendarIds(repo).first())
    }

    @Test
    fun `a stored id that is not a number is ignored rather than crashing`() = runTest {
        val ids = monthCalendarIds(
            FakeCalendarRepository(calendars = calendars),
            FakePreferences(monthHidden = setOf("not-an-id", "3")),
        ).first()
        assertEquals(setOf(1L, 2L), ids)
    }

    @Test
    fun `migration unticks each legacy calendar in the provider and forgets it`() = runTest {
        val repo = FakeCalendarRepository(calendars = calendars)
        val prefs = FakePreferences(legacyHidden = setOf("1", "3"))

        migrateLegacyHiddenCalendars(repo, prefs)

        assertEquals(listOf(1L to false, 3L to false), repo.visibilityWrites.sortedBy { it.first })
        assertEquals(setOf(2L), visibleCalendarIds(repo).first())
        assertEquals(emptySet<String>(), prefs.legacyHiddenCalendarIds.value)
    }

    @Test
    fun `migration drops ids with nothing left to do`() = runTest {
        val repo = FakeCalendarRepository(
            calendars = listOf(testCalendar(id = 1, visible = false), testCalendar(id = 2)),
        )
        // 1 is already unticked, 9 no longer exists, and the last is not an id at all.
        val prefs = FakePreferences(legacyHidden = setOf("1", "9", "junk"))

        migrateLegacyHiddenCalendars(repo, prefs)

        assertTrue(repo.visibilityWrites.isEmpty())
        assertEquals(emptySet<String>(), prefs.legacyHiddenCalendarIds.value)
    }

    // A retry on a later sync could untick a calendar the user has ticked since. Left ticked, the
    // calendar is at least visible to the user as ticked, and one tap away from what they meant.
    @Test
    fun `migration does not retry a write the provider refused`() = runTest {
        val repo = FakeCalendarRepository(calendars = calendars)
        repo.refuseCalendarWritesFor = setOf(3L)
        val prefs = FakePreferences(legacyHidden = setOf("1", "3"))

        migrateLegacyHiddenCalendars(repo, prefs)

        assertEquals(emptySet<String>(), prefs.legacyHiddenCalendarIds.value)
        assertEquals(setOf(2L, 3L), visibleCalendarIds(repo).first())
    }

    // An empty read is a missing permission or a wedged provider. Dropping the ids then would put
    // back every calendar the user had hidden.
    @Test
    fun `migration leaves the ids alone when no calendar can be read`() = runTest {
        val repo = FakeCalendarRepository(calendars = emptyList())
        val prefs = FakePreferences(legacyHidden = setOf("1", "3"))

        migrateLegacyHiddenCalendars(repo, prefs)

        assertEquals(setOf("1", "3"), prefs.legacyHiddenCalendarIds.value)
    }
}
