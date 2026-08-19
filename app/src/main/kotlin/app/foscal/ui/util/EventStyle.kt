package app.foscal.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import app.foscal.core.model.EventColorStrength

/**
 * The user's choices about how an event is drawn, carried down the tree the way
 * [LocalUse24HourClock] is.
 *
 * Ambient rather than threaded through parameters because the things that read them — a block in
 * the week grid, a bar in the all-day header, the fill helper — sit several composables below the
 * screen that could hold the state, and every one of them would otherwise need a parameter it does
 * nothing with but pass on. Static, so a change repaints the grid rather than recomposing every
 * reader individually; these move when the user moves a slider, not while scrolling.
 */
val LocalEventColorStrength = staticCompositionLocalOf { EventColorStrength.Default }

/** Multiplier on the built-in text sizes inside event blocks. 1f is the size the app ships with. */
val LocalEventTextScale = staticCompositionLocalOf { 1f }

/** Whether a title too long for its block wraps, or is cut off with an ellipsis on one line. */
val LocalWrapEventTitles = staticCompositionLocalOf { true }

/**
 * [this] at the user's event text scale.
 *
 * Guarded: an unspecified size means "inherit", and multiplying that produces NaN rather than an
 * inherited size.
 */
fun TextUnit.scaledBy(scale: Float): TextUnit = if (isSpecified) this * scale else this
