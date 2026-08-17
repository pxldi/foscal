package app.foscal.core.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * How long anything is allowed to move.
 *
 * These are deliberately short. Every transition in this app follows something the user did — a
 * tap, a swipe — and until it finishes the screen is showing a state that is on its way out. A
 * 300ms slide is a third of a second between "I swiped" and "I can read the month I asked for",
 * repeated on every navigation. The animation's job here is only to say which way the content
 * went; it is not the point of the interaction, so it gets out of the way quickly.
 */
object Motion {
    const val DurationShort = 90
    const val DurationMedium = 160
    const val DurationLong = 260

    const val DefaultAlpha = 1f
    const val DimmedAlpha = 0.0f
}

fun <T> motionTween(
    durationMillis: Int = Motion.DurationMedium,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> = tween(durationMillis = durationMillis, delayMillis = delayMillis)

fun <T> motionSpring(
    dampingRatio: Float = Spring.DampingRatioLowBouncy,
    stiffness: Float = Spring.StiffnessMediumLow,
): AnimationSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

fun slideOffset(durationMillis: Int = Motion.DurationMedium): FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = durationMillis)

fun dpSpring(): AnimationSpec<Dp> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
