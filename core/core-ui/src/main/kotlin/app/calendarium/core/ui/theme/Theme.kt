package app.calendarium.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.calendarium.core.model.AccentColor

private fun lightColorsFor(accent: AccentTokens) = lightColorScheme(
    primary = accent.primaryLight,
    onPrimary = Color.White,
    primaryContainer = accent.primaryContainerLight,
    onPrimaryContainer = accent.onPrimaryContainerLight,
    secondary = accent.primaryLight,
    onSecondary = Color.White,
    secondaryContainer = accent.primaryContainerLight,
    onSecondaryContainer = accent.onPrimaryContainerLight,
    background = CalendariumLightBackground,
    onBackground = Color(0xFF1F2328),
    surface = CalendariumLightSurface,
    onSurface = Color(0xFF1F2328),
    surfaceVariant = CalendariumLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF7D8793),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CalendariumLightSurfaceVariant,
    surfaceContainer = Color(0xFFEAEFF5),
    surfaceContainerHigh = Color(0xFFE5EBF2),
    outline = Color(0xFFC8CED8),
    outlineVariant = CalendariumLightOutline,
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
    background = CalendariumDarkBackground,
    onBackground = Color(0xFFE7EAEE),
    surface = CalendariumDarkSurface,
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = CalendariumDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA9B1BC),
    surfaceContainerLowest = Color(0xFF111419),
    surfaceContainerLow = CalendariumDarkSurfaceVariant,
    surfaceContainer = Color(0xFF262B33),
    surfaceContainerHigh = Color(0xFF2E343D),
    outline = Color(0xFF59616C),
    outlineVariant = CalendariumDarkOutline,
    error = Color(0xFFFF6B66),
)

@Composable
fun CalendariumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accent: AccentColor = AccentColor.Default,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val tokens = accent.tokens()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorsFor(tokens)
        else -> lightColorsFor(tokens)
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = CalendariumTypography,
        content = content,
    )
}

/** Maps a persisted [AccentColor] preset to its light/dark color tokens. */
fun AccentColor.tokens(): AccentTokens = when (this) {
    AccentColor.COBALT -> CobaltAccent
    AccentColor.VIOLET -> VioletAccent
    AccentColor.FOREST -> ForestAccent
}

/** Weekend day-of-week label color, adjusted so the gold stays legible on the dark surface. */
@Composable
fun weekendLabelColor(darkTheme: Boolean = isSystemInDarkTheme()): Color =
    if (darkTheme) WeekendGoldDark else WeekendGoldLight
