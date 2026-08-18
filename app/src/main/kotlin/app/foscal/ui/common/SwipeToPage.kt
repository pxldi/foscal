package app.foscal.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which way a horizontal swipe wants to go, or null if it has not travelled far enough yet. */
enum class SwipeDirection { Previous, Next }

/** Dragging left asks for what comes after, the way turning a page does. */
fun swipeDirection(totalX: Float, threshold: Float): SwipeDirection? = when {
    totalX <= -threshold -> SwipeDirection.Next
    totalX >= threshold -> SwipeDirection.Previous
    else -> null
}

/** Which gesture a drag is, decided from where it has got to rather than how it got there. */
enum class SwipeAxis { Horizontal, Vertical }

/**
 * Reads a drag's axis once it has travelled [slop] from where the finger went down.
 *
 * The comparison is against the total displacement, not the latest movement, so the wobble every
 * real finger starts with averages out instead of deciding the gesture. Null until the drag has
 * gone far enough to mean anything; ties go to horizontal, which is the intent that has to fight
 * for the gesture — a scrollable child will happily take everything else.
 */
fun swipeAxis(total: Offset, slop: Float): SwipeAxis? = when {
    total.getDistance() < slop -> null
    abs(total.x) >= abs(total.y) -> SwipeAxis.Horizontal
    else -> SwipeAxis.Vertical
}

/**
 * Pages back and forward on a horizontal swipe, over content that may scroll vertically.
 *
 * The axis is arbitrated here, before the children see the gesture at all. Compose delivers the
 * main pass to children first, so a vertical scroller underneath used to win every drag where its
 * own slop happened to be crossed first — which, on a diagonal flick, is most of them, and is why
 * a swipe meant for the next week could scroll the day instead. This watches the initial pass,
 * decides from the total displacement which gesture it is, and either claims it outright or gets
 * out of the way for good. The decision is taken at the same distance a scroller would start at,
 * and the initial pass runs first, so claiming always beats the scroller to it.
 *
 * Two decisions kept from before:
 *
 * It fires the moment the drag crosses [threshold], while the finger is still down. Waiting for the
 * lift means the swipe is over before anything happens, which reads as the app being slow rather
 * than as a deliberate confirm step. A latch holds it to one page per swipe — the threshold is
 * crossed long before a flick stops moving, so without it a single gesture skids through several.
 *
 * The page turn is confirmed with a tick, so a swipe that pages feels different in the hand from
 * one that scrolls, not just on screen.
 */
@Composable
fun Modifier.pageOnSwipe(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    threshold: Dp = 56.dp,
): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val haptics = LocalHapticFeedback.current
    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val thresholdPx = threshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var total = Offset.Zero
            var claimed = false
            var paged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                total += change.positionChangeIgnoreConsumed()
                if (!claimed) {
                    when (swipeAxis(total, slop)) {
                        null -> continue
                        // Not ours. Leave without having consumed anything, so whatever is
                        // underneath scrolls exactly as if this modifier were not here.
                        SwipeAxis.Vertical -> break
                        SwipeAxis.Horizontal -> claimed = true
                    }
                }
                change.consume()
                if (!paged) {
                    val direction = swipeDirection(total.x, thresholdPx) ?: continue
                    paged = true
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    when (direction) {
                        SwipeDirection.Next -> next()
                        SwipeDirection.Previous -> previous()
                    }
                }
            }
        }
    }
}
