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

private val LightColors = lightColorScheme(
    primary = CalendariumBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FF),
    onPrimaryContainer = Color(0xFF0C3B78),
    secondary = CalendariumBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F0FF),
    onSecondaryContainer = Color(0xFF0C3B78),
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

private val DarkColors = darkColorScheme(
    primary = CalendariumBlueDark,
    onPrimary = Color(0xFF07121F),
    primaryContainer = Color(0xFF183A66),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = CalendariumBlueDark,
    onSecondary = Color(0xFF07121F),
    secondaryContainer = Color(0xFF183A66),
    onSecondaryContainer = Color(0xFFD8E7FF),
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
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
