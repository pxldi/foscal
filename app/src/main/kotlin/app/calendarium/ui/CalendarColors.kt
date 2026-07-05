package app.calendarium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

object CalendarColors {
    private val defaults = listOf(
        0xFF1976D2.toInt(),
        0xFFD81B60.toInt(),
        0xFF43A047.toInt(),
        0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(),
        0xFF00897B.toInt(),
        0xFFE53935.toInt(),
        0xFF6D4C41.toInt(),
    )

    fun pick(index: Int): Int = defaults[index % defaults.size]
}

fun Int.toComposeColor(): Color = Color(this)

fun contrastColor(bg: Int): Color {
    val r = (bg shr 16) and 0xFF
    val g = (bg shr 8) and 0xFF
    val b = bg and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance > 0.55) Color.Black else Color.White
}
