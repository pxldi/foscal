package app.foscal.core.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

object Motion {
    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450

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
