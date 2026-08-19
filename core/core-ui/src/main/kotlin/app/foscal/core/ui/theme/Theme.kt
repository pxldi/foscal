package app.foscal.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.foscal.core.model.AccentColor

private fun lightColorsFor(accent: AccentTokens) = lightColorScheme(
    primary = accent.primaryLight,
    onPrimary = Color.White,
    primaryContainer = accent.primaryContainerLight,
    onPrimaryContainer = accent.onPrimaryContainerLight,
    secondary = accent.secondaryLight,
    onSecondary = Color.White,
    secondaryContainer = accent.secondaryContainerLight,
    onSecondaryContainer = accent.onSecondaryContainerLight,
    background = FoscalLightBackground,
    onBackground = Color(0xFF1F2328),
    surface = FoscalLightSurface,
    onSurface = Color(0xFF1F2328),
    surfaceVariant = FoscalLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF7D8793),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = FoscalLightSurfaceVariant,
    surfaceContainer = Color(0xFFEAEFF5),
    surfaceContainerHigh = Color(0xFFE5EBF2),
    outline = Color(0xFFC8CED8),
    outlineVariant = FoscalLightOutline,
    error = Color(0xFFE53935),
)

private fun darkColorsFor(accent: AccentTokens) = darkColorScheme(
    primary = accent.primaryDark,
    onPrimary = accent.onPrimaryDark,
    primaryContainer = accent.primaryContainerDark,
    onPrimaryContainer = accent.onPrimaryContainerDark,
    secondary = accent.secondaryDark,
    onSecondary = accent.onPrimaryDark,
    secondaryContainer = accent.secondaryContainerDark,
    onSecondaryContainer = accent.onSecondaryContainerDark,
    background = FoscalDarkBackground,
    onBackground = Color(0xFFE7EAEE),
    surface = FoscalDarkSurface,
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = FoscalDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA9B1BC),
    surfaceContainerLowest = Color(0xFF111419),
    surfaceContainerLow = FoscalDarkSurfaceVariant,
    surfaceContainer = Color(0xFF262B33),
    surfaceContainerHigh = Color(0xFF2E343D),
    outline = Color(0xFF59616C),
    outlineVariant = FoscalDarkOutline,
    error = Color(0xFFFF6B66),
)

/**
 * Whether the app is currently rendering dark. Reads the resolved theme — which the user can force
 * to Light or Dark in Settings — where [isSystemInDarkTheme] would only ever report the OS setting
 * and so disagree with the rest of the UI. Anything outside this file that needs to branch on
 * light/dark must read this, never [isSystemInDarkTheme].
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun FoscalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accent: AccentColor = AccentColor.Default,
    customSeed: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val tokens = if (accent == AccentColor.CUSTOM && customSeed != null) {
        customAccentTokens(customSeed)
    } else {
        accent.tokens()
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorsFor(tokens)
        else -> lightColorsFor(tokens)
    }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FoscalTypography,
            content = content,
        )
    }
}

/** Maps a persisted [AccentColor] preset to its light/dark color tokens. */
fun AccentColor.tokens(): AccentTokens = when (this) {
    AccentColor.COBALT -> CobaltAccent
    AccentColor.VIOLET -> VioletAccent
    AccentColor.FOREST -> ForestAccent
    // CUSTOM has no fixed tokens; callers pass the seed to customAccentTokens. Fall back to the
    // default preset if a seed isn't supplied.
    AccentColor.CUSTOM -> CobaltAccent
}

/**
 * Derives a full accent from a single [seed] color by blending it toward white/black, so any
 * user-picked color yields a coherent light/dark palette without hand-tuning each token.
 */
fun customAccentTokens(seed: Color): AccentTokens = AccentTokens(
    primaryLight = seed,
    primaryContainerLight = lerp(seed, Color.White, 0.86f),
    onPrimaryContainerLight = lerp(seed, Color.Black, 0.62f),
    primaryDark = lerp(seed, Color.White, 0.55f),
    onPrimaryDark = lerp(seed, Color.Black, 0.82f),
    primaryContainerDark = lerp(seed, Color.Black, 0.58f),
    onPrimaryContainerDark = lerp(seed, Color.White, 0.80f),
    // A step off the seed rather than a second hue: the presets pair their primary with a chosen
    // companion, and there is no way to choose one on the user's behalf from a single colour that
    // would not be a guess. Lighter and quieter is the safe reading of "secondary".
    secondaryLight = lerp(seed, Color.Black, 0.24f),
    secondaryContainerLight = lerp(seed, Color.White, 0.80f),
    onSecondaryContainerLight = lerp(seed, Color.Black, 0.70f),
    secondaryDark = lerp(seed, Color.White, 0.42f),
    secondaryContainerDark = lerp(seed, Color.Black, 0.66f),
    onSecondaryContainerDark = lerp(seed, Color.White, 0.72f),
)

/**
 * Amber where it is drawn as text rather than as a fill — weekend labels, today's weekday.
 *
 * The light form is the brand amber darkened until it clears 4.5:1 on white. The gold this
 * replaced sat at 2.5:1, which was both off-palette and not actually readable.
 */
@Composable
fun amberTextColor(darkTheme: Boolean = LocalIsDarkTheme.current): Color =
    if (darkTheme) AmberTextDark else AmberTextLight

/** Weekend day-of-week label colour. */
@Composable
fun weekendLabelColor(darkTheme: Boolean = LocalIsDarkTheme.current): Color =
    amberTextColor(darkTheme)

/**
 * The filled disc marking today, and the ink on it.
 *
 * Amber rather than the accent, so the icon on the home screen is a literal preview of the app —
 * and so that today stops looking like a selected day, which was the other blue disc.
 */
@Composable
fun todayDiscColor(): Color = FoscalAmber

@Composable
fun onTodayDiscColor(): Color = AmberInk
