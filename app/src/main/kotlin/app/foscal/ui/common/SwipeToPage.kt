package app.foscal.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which way a horizontal swipe wants to go, or null if it has not travelled far enough yet. */
enum class SwipeDirection { Previous, Next }

/** Dragging left asks for what comes after, the way turning a page does. */
fun swipeDirection(totalX: Float, threshold: Float): SwipeDirection? = when {
    totalX <= -threshold -> SwipeDirection.Next
    totalX >= threshold -> SwipeDirection.Previous
    else -> null
}

/**
 * Pages back and forward on a horizontal swipe.
 *
 * Two decisions worth keeping:
 *
 * It fires the moment the drag crosses [threshold], while the finger is still down. Waiting for the
 * lift means the swipe is over before anything happens, which reads as the app being slow rather
 * than as a deliberate confirm step. A latch, cleared when the next gesture starts, holds it to one
 * page per swipe — the threshold is crossed long before a flick stops moving, so without it a
 * single gesture skids through several.
 *
 * It uses horizontal drag detection rather than a general one, which is what lets it sit over
 * content that scrolls vertically or has draggable children: the gesture only starts once the
 * *horizontal* slop is crossed, so a vertical scroll and a drag that a child has already claimed
 * both pass through untouched.
 */
@Composable
fun Modifier.pageOnSwipe(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    threshold: Dp = 56.dp,
): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    return this.pointerInput(Unit) {
        val thresholdPx = threshold.toPx()
        var total = 0f
        var handled = false
        detectHorizontalDragGestures(
            onDragStart = {
                total = 0f
                handled = false
            },
        ) { change, dragAmount ->
            if (handled) return@detectHorizontalDragGestures
            total += dragAmount
            change.consume()
            when (swipeDirection(total, thresholdPx)) {
                SwipeDirection.Next -> {
                    handled = true
                    next()
                }
                SwipeDirection.Previous -> {
                    handled = true
                    previous()
                }
                null -> Unit
            }
        }
    }
}
