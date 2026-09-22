package app.foscal.ui.feedback

import app.foscal.at
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.timedEvent
import app.foscal.ui.editor.RecurrenceScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeletesTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeCalendarRepository()
    private val messages = UserMessages()
    private val deletes = PendingDeletes(repo, messages, CoroutineScope(dispatcher))

    private val day = LocalDate.of(2026, 9, 21)

    @Test
    fun `nothing is written while the undo window is open`() = runTest(dispatcher) {
        deletes.request(10L, 1_000L, RecurrenceScope.ALL_EVENTS, "Standup")
        advanceTimeBy(PendingDeletes.UNDO_WINDOW_MILLIS - 1)

        assertNull(repo.lastOp)
        assertEquals(1, deletes.pending.value.size)
    }

    @Test
    fun `undo inside the window writes nothing at all`() = runTest(dispatcher) {
        deletes.request(10L, 1_000L, RecurrenceScope.ALL_EVENTS, "Standup")
        advanceTimeBy(1_000L)
        deletes.undo(deletes.pending.value.single().key)
        advanceUntilIdle()

        assertNull(repo.lastOp)
        assertTrue(deletes.pending.value.isEmpty())
    }

    @Test
    fun `the window running out writes the delete at the scope asked for`() = runTest(dispatcher) {
        deletes.request(10L, 1_000L, RecurrenceScope.SINGLE, "Standup")
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_INSTANCE, repo.lastOp)
        assertEquals(listOf(10L to 1_000L), repo.instanceDeletes)

        deletes.request(10L, 1_000L, RecurrenceScope.THIS_AND_FOLLOWING, "Standup")
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_FOLLOWING, repo.lastOp)

        deletes.request(10L, 1_000L, RecurrenceScope.ALL_EVENTS, "Standup")
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE, repo.lastOp)

        assertTrue(deletes.pending.value.isEmpty())
    }

    @Test
    fun `undo after the write has gone through changes nothing`() = runTest(dispatcher) {
        deletes.request(10L, 1_000L, RecurrenceScope.ALL_EVENTS, "Standup")
        val key = deletes.pending.value.single().key
        advanceUntilIdle()
        repo.reset()

        deletes.undo(key)
        advanceUntilIdle()

        assertNull(repo.lastOp)
    }

    @Test
    fun `a refused delete brings the event back and says so`() = runTest(dispatcher) {
        repo.refuseWrites = true
        deletes.request(10L, 1_000L, RecurrenceScope.ALL_EVENTS, "Standup")
        advanceUntilIdle()

        assertTrue(deletes.pending.value.isEmpty())
        assertEquals("Couldn't delete “Standup”", messages.messages.first())
    }

    @Test
    fun `each scope hides exactly the occurrences it will remove`() {
        val mon = timedEvent(10, at(day, 9), at(day, 10))
        val tue = timedEvent(10, at(day.plusDays(1), 9), at(day.plusDays(1), 10))
        val wed = timedEvent(10, at(day.plusDays(2), 9), at(day.plusDays(2), 10))
        val other = timedEvent(11, at(day.plusDays(1), 9), at(day.plusDays(1), 10))
        val all = listOf(mon, tue, wed, other)
        fun hidden(scope: RecurrenceScope) =
            all.filter { PendingDelete(1, 10, tue.start.toEpochMilli(), scope, "").hides(it) }

        assertEquals(listOf(tue), hidden(RecurrenceScope.SINGLE))
        assertEquals(listOf(tue, wed), hidden(RecurrenceScope.THIS_AND_FOLLOWING))
        assertEquals(listOf(mon, tue, wed), hidden(RecurrenceScope.ALL_EVENTS))
    }

    @Test
    fun `the views stop drawing a pending delete and draw it again on undo`() =
        runTest(dispatcher) {
            val event = timedEvent(10, at(day, 9), at(day, 10))
            val shown = MutableStateFlow(listOf(event)).withoutPendingDeletes(deletes)

            deletes.request(10L, event.start.toEpochMilli(), RecurrenceScope.ALL_EVENTS, "")
            assertTrue(shown.first().isEmpty())

            deletes.undo(deletes.pending.value.single().key)
            assertEquals(listOf(event), shown.first())
            assertTrue(deletes.pending.value.isEmpty())
        }
}
