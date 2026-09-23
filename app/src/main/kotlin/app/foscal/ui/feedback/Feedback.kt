package app.foscal.ui.feedback

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The one snackbar queue for the whole app.
 *
 * Each screen's Scaffold mounts it through [FeedbackSnackbarHost], so a message lands above that
 * screen's bottom bar. A single queue, because a delete's Undo is shown on the screen the user
 * returns to, not on the one that asked for it.
 */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun FeedbackSnackbarHost(modifier: Modifier = Modifier) =
    SnackbarHost(LocalSnackbarHostState.current, modifier)

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    val messages: UserMessages,
    val pendingDeletes: PendingDeletes,
) : ViewModel()

/** Shows every posted message and an Undo for the newest pending delete. */
@Composable
fun FeedbackEffects(host: SnackbarHostState, viewModel: FeedbackViewModel) {
    LaunchedEffect(host, viewModel) {
        viewModel.messages.messages.collect { host.showSnackbar(it) }
    }
    LaunchedEffect(host, viewModel) {
        viewModel.pendingDeletes.pending
            .map { it.lastOrNull() }
            .distinctUntilChanged()
            .collectLatest { newest ->
                newest ?: return@collectLatest
                // Indefinite, because the delete's own timer decides when it is too late to undo.
                // When the write goes through, the pending list changes, collectLatest cancels
                // this call, and cancelling it dismisses the snackbar.
                val result = host.showSnackbar(
                    message = "Deleted “${newest.title}”",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.pendingDeletes.undo(newest.key)
                }
            }
    }
}
