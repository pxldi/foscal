package app.foscal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.foscal.core.ui.theme.LocalIsDarkTheme
import app.foscal.ui.util.LocalEventColorStrength

/** The two colours an event is drawn in, derived once from its calendar's colour. */
data class EventColors(
    /** The block's fill: the event's colour, at full strength. */
    val container: Color,
    /** Its text, black or white — whichever actually reads on [container]. */
    val content: Color,
    /** The event's colour untouched, for anything that needs the source rather than the fill. */
    val accent: Color,
)

/**
 * How an event should be painted, given its calendar's colour.
 *
 * Solid. A tinted block has to be pale enough for dark text to sit on it, which means the colour
 * stops carrying any weight of its own — a screen of them reads as grey boxes with a hint of hue,
 * and telling one calendar from another takes a deliberate look rather than a glance. Filling with
 * the colour and choosing text to suit puts the readability back where it belongs.
 *
 * Dark theme pulls the fill toward the surface. The same saturated colour that reads as confident
 * on white glares on near-black, and a wall of them is exhausting.
 *
 * How far past that it goes is the user's to choose, through
 * [app.foscal.core.model.EventColorStrength]. Whatever they pick, the ink is chosen against the
 * fill that comes out and then nudged for contrast, so a softer wall of blocks is still readable
 * rather than merely quieter.
 */
@Composable
fun eventColors(eventColorArgb: Int): EventColors {
    val dark = LocalIsDarkTheme.current
    val surface = MaterialTheme.colorScheme.surface
    val strength = LocalEventColorStrength.current
    return remember(eventColorArgb, dark, surface, strength) {
        val accent = Color(eventColorArgb)
        // Dark theme's own 30% is the floor, and the user's wash is spent on what is left of the
        // way to the surface — otherwise "Very soft" in dark mode lands past the background and
        // the block stops being an object on the grid.
        val wash = if (dark) 0.30f + strength.wash * 0.70f else strength.wash
        val base = if (wash > 0f) lerp(accent, surface, wash) else accent
        val ink = readableOn(base)
        EventColors(container = legible(base, ink), content = ink, accent = accent)
    }
}

/**
 * [fill] nudged away from [ink] until the two clear 4.5:1.
 *
 * Mid-luminance colours — an orange, a teal, a red — sit almost equidistant from black and white,
 * and land near 4.2:1 against whichever is closer. Three of the app's own eight presets did. The
 * shift needed is 3–4% and is not perceptible; the cap is there for a colour where no amount of
 * it would help, which is better than looping toward grey.
 */
private fun legible(fill: Color, ink: Color): Color {
    val away = if (ink == Color.White) Color.Black else Color.White
    var step = 0
    var candidate = fill
    while (contrastRatio(candidate, ink) < 4.5f && step < 30) {
        step++
        candidate = lerp(fill, away, step * 0.01f)
    }
    return candidate
}

/**
 * Black or white on [background], whichever has the better ratio.
 *
 * Not a luminance threshold: a mid-tone teal sits either side of one depending where it is drawn,
 * and picking the wrong side there costs more contrast than the threshold ever saves. Near-black
 * rather than black, which is less of a hole in a saturated fill.
 */
private fun readableOn(background: Color): Color {
    val ink = Color(0xFF16181B)
    return if (contrastRatio(background, ink) >= contrastRatio(background, Color.White)) {
        ink
    } else {
        Color.White
    }
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}
