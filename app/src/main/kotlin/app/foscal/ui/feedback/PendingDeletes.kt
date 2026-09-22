package app.foscal.ui.feedback

import app.foscal.core.data.CalendarRepository
import app.foscal.core.model.Event
import app.foscal.ui.editor.RecurrenceScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A delete the user asked for that has not reached the provider yet. */
data class PendingDelete(
    val key: Long,
    val eventId: Long,
    val instanceStartMillis: Long,
    val scope: RecurrenceScope,
    val title: String,
) {
    /**
     * Whether [event] is one of the occurrences this delete will remove.
     *
     * An exception row of the series has an id of its own, so a moved occurrence stays on screen
     * during the undo window of an all-events delete. The provider removes it with the master.
     */
    fun hides(event: Event): Boolean = event.id == eventId && when (scope) {
        RecurrenceScope.SINGLE -> event.start.toEpochMilli() == instanceStartMillis
        RecurrenceScope.THIS_AND_FOLLOWING -> event.start.toEpochMilli() >= instanceStartMillis
        RecurrenceScope.ALL_EVENTS -> true
    }
}

/**
 * Holds each delete for [UNDO_WINDOW_MILLIS] before writing it, so Undo has nothing to put back.
 *
 * Re-creating a deleted event is not a faithful undo. A series comes back without its exception
 * rows and under a new id, and on a synced calendar the new event is sent to the server with its
 * guest list, which is a fresh invitation from the user. Holding the write avoids both.
 *
 * The cost is that a delete still waiting when the process dies never happens. The event is then
 * still there, which is the safe way to be wrong.
 */
@Singleton
class PendingDeletes internal constructor(
    private val repository: CalendarRepository,
    private val messages: UserMessages,
    private val scope: CoroutineScope,
) {
    // The scope is private to this object, not an injectable application scope: nothing else gets
    // to start unsupervised work on it.
    @Inject
    constructor(repository: CalendarRepository, messages: UserMessages) :
        this(repository, messages, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _pending = MutableStateFlow<List<PendingDelete>>(emptyList())
    val pending: StateFlow<List<PendingDelete>> = _pending.asStateFlow()

    private val lock = Any()
    private val timers = mutableMapOf<Long, Job>()
    private var nextKey = 0L

    /** Removes the occurrences [scope] describes once the undo window passes without an undo. */
    fun request(eventId: Long, instanceStartMillis: Long, scope: RecurrenceScope, title: String) {
        val item = synchronized(lock) {
            PendingDelete(++nextKey, eventId, instanceStartMillis, scope, title)
        }
        _pending.update { it + item }
        val timer = this.scope.launch {
            delay(UNDO_WINDOW_MILLIS)
            commit(item)
        }
        synchronized(lock) { timers[item.key] = timer }
    }

    /**
     * Drops the delete with [key]. Does nothing once its write has started: the write is already
     * on its way to the provider, and the item leaves [pending] when it lands.
     */
    fun undo(key: Long) {
        val timer = synchronized(lock) { timers.remove(key) } ?: return
        timer.cancel()
        _pending.update { list -> list.filterNot { it.key == key } }
    }

    private suspend fun commit(item: PendingDelete) {
        // Claiming the timer is what makes a late Undo a no-op instead of a half-undone write.
        synchronized(lock) { timers.remove(item.key) } ?: return
        val start = item.instanceStartMillis
        val gone = when (item.scope) {
            RecurrenceScope.SINGLE -> repository.deleteEventInstance(item.eventId, start)
            RecurrenceScope.THIS_AND_FOLLOWING -> repository.deleteEventFollowing(item.eventId, start)
            RecurrenceScope.ALL_EVENTS -> repository.deleteEvent(item.eventId)
        }
        // Released after the write, not before, so the event does not reappear for the few frames
        // the provider's change takes to come back through the views' flows.
        _pending.update { list -> list.filterNot { it.key == item.key } }
        if (!gone) messages.post("Couldn't delete “${item.title}”")
    }

    companion object {
        /** As long as a Material long snackbar, which is what shows the Undo. */
        const val UNDO_WINDOW_MILLIS = 10_000L
    }
}

/** [events] without the occurrences a pending delete is about to remove. */
fun Flow<List<Event>>.withoutPendingDeletes(pendingDeletes: PendingDeletes): Flow<List<Event>> =
    combine(pendingDeletes.pending) { events, pending ->
        if (pending.isEmpty()) events else events.filterNot { e -> pending.any { it.hides(e) } }
    }
