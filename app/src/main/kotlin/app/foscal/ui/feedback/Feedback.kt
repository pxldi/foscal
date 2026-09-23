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

/** Shows every posted message and one Undo for every delete still waiting. */
@Composable
fun FeedbackEffects(host: SnackbarHostState, viewModel: FeedbackViewModel) {
    LaunchedEffect(host, viewModel) {
        viewModel.messages.messages.collect { host.showSnackbar(it) }
    }
    LaunchedEffect(host, viewModel) {
        viewModel.pendingDeletes.undoable.collectLatest { waiting ->
            if (waiting.isEmpty()) return@collectLatest
            // Indefinite, because PendingDeletes decides when it is too late to undo. A new delete
            // or the write starting changes the list, collectLatest cancels this call, and
            // cancelling it dismisses the snackbar.
            val result = host.showSnackbar(
                message = undoMessage(waiting),
                actionLabel = "Undo",
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.pendingDeletes.undo(waiting.map { it.key })
            }
        }
    }
}
