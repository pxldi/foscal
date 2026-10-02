package app.foscal.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.foscal.R
import app.foscal.ui.editor.RecurrenceScope

/** What the user is about to do to a series, which the dialog's wording names. */
enum class ScopeAction { CHANGE, MOVE }

/**
 * "This event, this and following, or all of them?" — asked wherever a series can be changed.
 *
 * One dialog rather than a copy per caller. There are three places that have to ask (saving an
 * edit, deleting, and dropping an occurrence somewhere else on the grid) and the answer means the
 * same thing in all three, so the wording should not drift between them. [action] names what the
 * user is about to do, in the imperative, because "Move this event" reads as a button and "Apply
 * your change to this event" does not. Each choice is a whole string per action rather than a verb
 * spliced into a sentence, since the verb does not sit in the same place in every language.
 */
@Composable
fun RecurrenceScopeDialog(
    action: ScopeAction,
    onScope: (RecurrenceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val choose: (RecurrenceScope) -> Unit = { scope ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onScope(scope)
    }
    val move = action == ScopeAction.MOVE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (move) R.string.scope_move_title else R.string.scope_change_title))
        },
        text = {
            Column {
                Text(stringResource(R.string.scope_message))
                Spacer(Modifier.height(16.dp))
                ScopeChoice(
                    stringResource(if (move) R.string.scope_move_this else R.string.scope_change_this),
                ) { choose(RecurrenceScope.SINGLE) }
                ScopeChoice(
                    stringResource(
                        if (move) R.string.scope_move_following else R.string.scope_change_following,
                    ),
                ) { choose(RecurrenceScope.THIS_AND_FOLLOWING) }
                ScopeChoice(
                    stringResource(if (move) R.string.scope_move_all else R.string.scope_change_all),
                ) { choose(RecurrenceScope.ALL_EVENTS) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ScopeChoice(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
