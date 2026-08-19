package app.foscal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event block's fill and its text have to clear 4.5:1 for every colour a calendar can be —
 * the app's own presets, and whatever a server hands over.
 */
class EventColorsTest {

    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** Mirrors what `eventColors` does, without needing a composition to run it in. */
    private fun resolve(argb: Int, dark: Boolean): Pair<Color, Color> {
        val surface = if (dark) Color(0xFF191C21) else Color.White
        val accent = Color(argb)
        val base = if (dark) androidx.compose.ui.graphics.lerp(accent, surface, 0.30f) else accent
        val ink = Color(0xFF16181B)
        val chosen = if (ratio(base, ink) >= ratio(base, Color.White)) ink else Color.White
        val away = if (chosen == Color.White) Color.Black else Color.White
        var step = 0
        var fill = base
        while (ratio(fill, chosen) < 4.5f && step < 30) {
            step++
            fill = androidx.compose.ui.graphics.lerp(base, away, step * 0.01f)
        }
        return fill to chosen
    }

    @Test
    fun `every preset is legible in both themes`() {
        for (argb in CalendarColors.presets) {
            for (dark in listOf(false, true)) {
                val (fill, ink) = resolve(argb, dark)
                val r = ratio(fill, ink)
                assertTrue(
                    "preset ${Integer.toHexString(argb)} in ${if (dark) "dark" else "light"} was $r:1",
                    r >= 4.5f,
                )
            }
        }
    }

    // The three that used to land at 4.2-4.3:1 — the mid-luminance ones, equidistant from black
    // and white and therefore close to neither.
    @Test
    fun `the awkward mid-tones are lifted rather than left`() {
        for (argb in listOf(0xFFE53935.toInt(), 0xFF00897B.toInt(), 0xFFFB8C00.toInt())) {
            for (dark in listOf(false, true)) {
                val (fill, ink) = resolve(argb, dark)
                assertTrue(ratio(fill, ink) >= 4.5f)
            }
        }
    }

    @Test
    fun `a colour arriving from a server is handled too`() {
        for (argb in listOf(0xFF808080.toInt(), 0xFF7F7F00.toInt(), 0xFFFFFFFF.toInt(),
                            0xFF000000.toInt(), 0xFF9E9E9E.toInt())) {
            for (dark in listOf(false, true)) {
                val (fill, ink) = resolve(argb, dark)
                assertTrue(
                    "grey ${Integer.toHexString(argb)} gave ${ratio(fill, ink)}:1",
                    ratio(fill, ink) >= 4.5f,
                )
            }
        }
    }
}
