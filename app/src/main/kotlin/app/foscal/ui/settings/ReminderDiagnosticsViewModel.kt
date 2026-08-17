package app.foscal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.ReminderSyncStatus
import app.foscal.notifications.ReminderHealth
import app.foscal.notifications.ReminderHealthCheck
import app.foscal.notifications.ReminderHealthProbe
import app.foscal.notifications.ReminderIssue
import app.foscal.notifications.ReminderSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ReminderDiagnosticsUiState(
    val loading: Boolean = true,
    val health: ReminderHealth? = null,
    val issues: List<ReminderIssue> = emptyList(),
    val resyncing: Boolean = false,
)

/**
 * Backs the "Not getting reminders?" panel.
 *
 * Almost everything it reports can change while Foscal is in the background — the user walks off to
 * the system settings screen the panel just sent them to and comes back — so the state re-probes on
 * [refresh] (called from the screen's resume) rather than being read once when the panel opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReminderDiagnosticsViewModel @Inject constructor(
    private val probe: ReminderHealthProbe,
    private val syncScheduler: ReminderSyncScheduler,
    status: ReminderSyncStatus,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0L)
    private val resyncRequestedAt = MutableStateFlow<Instant?>(null)

    val state: StateFlow<ReminderDiagnosticsUiState> = combine(
        refreshes,
        // The status store is written by the sync worker on every pass, so folding it in makes the
        // panel update itself when a sync lands — no polling, and no "last synced" timestamp left
        // showing a time the worker has already moved past.
        status.snapshot,
        resyncRequestedAt,
    ) { _, _, requestedAt -> requestedAt }
        // mapLatest, so a burst of syncs cancels the in-flight probe instead of queueing several.
        .mapLatest { requestedAt ->
            val health = probe.probe()
            ReminderDiagnosticsUiState(
                loading = false,
                health = health,
                issues = ReminderHealthCheck.issues(health, Instant.now()),
                resyncing = requestedAt != null && !hasSyncedSince(health, requestedAt),
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ReminderDiagnosticsUiState(),
        )

    private fun hasSyncedSince(health: ReminderHealth, requestedAt: Instant): Boolean {
        val last = health.lastSyncAt ?: return false
        return !last.isBefore(requestedAt)
    }

    /** Re-reads every permission and restriction. Cheap; safe to call on every resume. */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    fun resync() {
        val requestedAt = Instant.now()
        resyncRequestedAt.value = requestedAt
        syncScheduler.syncNow()
        viewModelScope.launch {
            // The spinner is cleared by the status write in the normal case. This is the backstop
            // for the case the panel exists to diagnose: work that never runs at all. A spinner
            // that turns forever is a worse answer than one that gives up.
            delay(RESYNC_TIMEOUT_MILLIS)
            resyncRequestedAt.compareAndSet(requestedAt, null)
        }
    }

    private companion object {
        const val RESYNC_TIMEOUT_MILLIS = 30_000L
    }
}
