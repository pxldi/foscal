package app.foscal.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Whether the UI renders clock times in 24-hour (`HH:mm`) or 12-hour (`h:mm a`) form. Set once at
 * the top of the tree from the user's preference; screens read it — directly in composable scope,
 * or by threading the resolved boolean into non-composable label helpers.
 */
val LocalUse24HourClock = staticCompositionLocalOf { true }

/** Time-of-day formatter for the given clock preference. */
fun timeFormatter(use24Hour: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())

/** Convenience for composable call sites: the formatter for the ambient clock preference. */
@Composable
@ReadOnlyComposable
fun rememberTimeFormatter(): DateTimeFormatter = timeFormatter(LocalUse24HourClock.current)
