package app.foscal.ui.util

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked Android for no animation ("Remove animations", or an animator
 * duration scale of 0 in developer options).
 *
 * Compose's own tweens already collapse to their end value at that scale, but anything paced by a
 * `delay`, or an infinite transition that is only decoration, does not know to stop. Motion that is
 * there purely for character checks this and shows its finished state instead.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
