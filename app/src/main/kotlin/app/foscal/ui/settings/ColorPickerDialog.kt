package app.foscal.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.foscal.ui.contrastColor
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Twenty-four ready-made accents, six to a row. Almost nobody wants a specific hex value — they want
 * a colour they like — so the grid is the fast path and the wheel below it is the escape hatch.
 */
private val PaletteColors = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5,
    0xFF039BE5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33,
    0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41, 0xFF546E7A,
    0xFFB71C1C, 0xFF880E4F, 0xFF4A148C, 0xFF0D47A1, 0xFF004D40, 0xFF1B5E20,
).map { it.toInt() }

/** Full-saturation hue stops for the wheel's sweep, closing the ring back on red. */
private val HueRing = listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) }

/**
 * A dependency-free colour picker: a palette to tap, a hue/saturation wheel to drag, and a
 * brightness bar. Emits the chosen ARGB value on confirm.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var brightness by remember { mutableFloatStateOf(initial[2]) }

    val color = Color.hsv(hue, saturation, brightness)
    val argb = color.toArgb()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(argb) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Custom color") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Palette(
                    selected = argb,
                    onPick = { picked ->
                        // Routed through HSV rather than kept as its own "a swatch is selected"
                        // state, so the wheel and bar jump to the tapped colour and stay the one
                        // source of truth.
                        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(picked, it) }
                        hue = hsv[0]
                        saturation = hsv[1]
                        brightness = hsv[2]
                    },
                )
                HorizontalDivider()
                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onChange = { h, s ->
                        hue = h
                        saturation = s
                    },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(184.dp),
                )
                BrightnessBar(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onChange = { brightness = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(color)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp),
                            ),
                    )
                    Text(
                        text = "#%06X".format(Locale.US, 0xFFFFFF and argb),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
    )
}

@Composable
private fun Palette(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PaletteColors.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { swatch ->
                    Swatch(
                        argb = swatch,
                        selected = swatch matches selected,
                        onClick = { onPick(swatch) },
                    )
                }
            }
        }
    }
}

/**
 * Whether two colours are the same to the eye.
 *
 * Tapping a swatch stores it as HSV, and HSV is float maths: converting back can land a channel one
 * step off where it started. An exact `==` would then fail to tick the very swatch just tapped, so
 * allow each channel to be out by one.
 */
private infix fun Int.matches(other: Int): Boolean =
    (0..16 step 8).all { shift ->
        val delta = ((this shr shift) and 0xFF) - ((other shr shift) and 0xFF)
        delta in -1..1
    }

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = contrastColor(argb),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Hue around the rim, saturation out from the centre. One gesture sets both, which is the whole
 * reason a wheel beats a pair of sliders: you aim at the colour instead of solving for it.
 */
@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by rememberUpdatedState(onChange)

    // A drag that leaves the wheel keeps tracking at full saturation rather than snapping back to
    // wherever the finger last was inside it.
    fun emit(position: Offset, bounds: IntSize) {
        val radius = min(bounds.width, bounds.height) / 2f
        val dx = position.x - bounds.width / 2f
        val dy = position.y - bounds.height / 2f
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        report((degrees + 360f) % 360f, (hypot(dx, dy) / radius).coerceIn(0f, 1f))
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { emit(it, size) }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { emit(it, size) }) { change, _ ->
                    emit(change.position, size)
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Brush.sweepGradient(HueRing, centre), radius, centre)
            drawCircle(
                Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, radius),
                radius,
                centre,
            )
            // Brightness has no position on the wheel, so it shows as the whole wheel dimming.
            // Without this a dark colour would leave the thumb sitting on a bright patch that is
            // not the colour being chosen.
            if (brightness < 1f) {
                drawCircle(Color.Black.copy(alpha = 1f - brightness), radius, centre)
            }
            val radians = Math.toRadians(hue.toDouble())
            val thumb = Offset(
                centre.x + cos(radians).toFloat() * saturation * radius,
                centre.y + sin(radians).toFloat() * saturation * radius,
            )
            drawCircle(Color.White, 11.dp.toPx(), thumb)
            drawCircle(Color.hsv(hue, saturation, brightness), 8.dp.toPx(), thumb)
        }
    }
}

/**
 * Brightness as a black-to-full-colour bar. The gradient says what the control does, so it needs no
 * label the way a bare slider would.
 */
@Composable
private fun BrightnessBar(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float) -> Unit,
) {
    val report by rememberUpdatedState(onChange)

    fun emit(position: Offset, bounds: IntSize) {
        report((position.x / bounds.width).coerceIn(0f, 1f))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                detectTapGestures { emit(it, size) }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { emit(it, size) }) { change, _ ->
                    emit(change.position, size)
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val track = 14.dp.toPx()
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.hsv(hue, saturation, 1f)),
                ),
                topLeft = Offset(0f, (size.height - track) / 2f),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(track / 2f),
            )
            val thumbRadius = 11.dp.toPx()
            val centre = Offset(
                (brightness * size.width).coerceIn(thumbRadius, size.width - thumbRadius),
                size.height / 2f,
            )
            drawCircle(Color.White, thumbRadius, centre)
            drawCircle(Color.hsv(hue, saturation, brightness), thumbRadius - 3.dp.toPx(), centre)
        }
    }
}
