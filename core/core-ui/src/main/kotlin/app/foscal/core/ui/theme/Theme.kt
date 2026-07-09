package app.foscal.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.foscal.core.model.AccentColor

private fun lightColorsFor(accent: AccentTokens) = lightColorScheme(
    primary = accent.primaryLight,
    onPrimary = Color.White,
    primaryContainer = accent.primaryContainerLight,
    onPrimaryContainer = accent.onPrimaryContainerLight,
    secondary = accent.primaryLight,
    onSecondary = Color.White,
    secondaryContainer = accent.primaryContainerLight,
    onSecondaryContainer = accent.onPrimaryContainerLight,
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
    secondary = accent.primaryDark,
    onSecondary = accent.onPrimaryDark,
    secondaryContainer = accent.primaryContainerDark,
    onSecondaryContainer = accent.onPrimaryContainerDark,
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
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FoscalTypography,
        content = content,
    )
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
)

/** Weekend day-of-week label color, adjusted so the gold stays legible on the dark surface. */
@Composable
fun weekendLabelColor(darkTheme: Boolean = isSystemInDarkTheme()): Color =
    if (darkTheme) WeekendGoldDark else WeekendGoldLight
