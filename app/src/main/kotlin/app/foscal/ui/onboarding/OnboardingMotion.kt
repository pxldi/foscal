package app.foscal.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.VectorConfig
import androidx.compose.ui.graphics.vector.VectorProperty
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foscal.R
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.Motion
import app.foscal.core.ui.theme.onTodayDiscColor
import app.foscal.core.ui.theme.todayDiscColor
import app.foscal.core.ui.theme.weekendLabelColor
import app.foscal.ui.util.Dates
import app.foscal.ui.util.currentLocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * The timing of the onboarding's motion, as plain functions of a progress value so it can be
 * checked without a frame clock.
 *
 * Everything here decorates a moment that exists anyway — the first screen being read, or one of
 * the two waits after a permission grant — and nothing gates a control on it. The welcome button
 * takes a tap on its first frame, even while it is still fading in.
 */
internal object OnboardingMotion {
    /** The whole welcome intro, mark first and text after it. */
    const val IntroMillis = 1100

    /** One sweep of the wave across the grid on the first wait. */
    const val ScanPeriodMillis = 900

    /** The grid filling on the second wait; shorter than its 1.1 s so the result is held. */
    const val SettleMillis = 850

    /** Where the mark's ground has finished arriving, as a fraction of the intro. */
    const val GroundEnd = 0.35f
    private const val CellStart = 0.38f
    private const val CellStagger = 0.045f
    private const val CellLength = 0.2f
    const val TodayStart = 0.8f

    /** Progress of a sub-animation running from [start] for [length], clamped to 0..1. */
    fun phase(t: Float, start: Float, length: Float): Float =
        ((t - start) / length).coerceIn(0f, 1f)

    /** An ease-out that overshoots and settles, for things that land. 0 at 0, 1 at 1. */
    fun backOut(x: Float, overshoot: Float = 1.70158f): Float {
        val p = x - 1f
        return 1f + (overshoot + 1f) * p * p * p + overshoot * p * p
    }

    fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

    /** Scale of the squircle the mark sits on. */
    fun groundScale(t: Float): Float = 0.4f + 0.6f * backOut(phase(t, 0f, GroundEnd))

    /** Scale of grid cell [index] (0..7, reading order, today excluded). */
    fun cellScale(t: Float, index: Int): Float =
        backOut(phase(t, CellStart + index * CellStagger, CellLength))

    /** The amber today-dot lands last, with a little more bounce than the cells. */
    fun todayScale(t: Float): Float = backOut(phase(t, TodayStart, 1f - TodayStart), overshoot = 2.6f)

    /**
     * How lit cell ([row], [col]) is while the wave sweeps the grid, for wave position [t].
     * The wave runs along the diagonal so it reads as a scan rather than a blink.
     */
    fun scanIntensity(t: Float, row: Int, col: Int, rows: Int, cols: Int): Float {
        val position = (row + col).toFloat() / (rows + cols - 2)
        val front = t * 1.5f - 0.25f
        return (1f - abs(front - position) * 4f).coerceIn(0f, 1f)
    }

    /** How filled cell [index] of [count] is while the grid settles. */
    fun settleFill(t: Float, index: Int, count: Int): Float =
        easeOut(phase(t, index.toFloat() / count * 0.65f, 0.15f))
}

/** A 0→1 clock that runs once over [durationMillis], or starts at 1 when [play] is false. */
@Composable
internal fun rememberPlayOnce(play: Boolean, durationMillis: Int): State<Float> {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
    }
    return progress.asState()
}

/**
 * The Foscal mark assembling itself: the ground springs in, the card fades up onto it, the days
 * pop in one after another and today lands last.
 *
 * Rendered from `ic_foscal_badge.xml` itself, with its named groups driven through [VectorConfig],
 * so the animated mark cannot drift from the launcher icon — it has no geometry of its own.
 */
@Composable
internal fun AnimatedFoscalMark(progress: State<Float>, modifier: Modifier = Modifier) {
    val vector = ImageVector.vectorResource(R.drawable.ic_foscal_badge)
    val markScale = 1.39f
    val configs = remember(progress) {
        fun scaled(scale: () -> Float) = object : VectorConfig {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T =
                when (property) {
                    VectorProperty.ScaleX, VectorProperty.ScaleY -> scale() as T
                    else -> defaultValue
                }
        }
        fun faded(alpha: () -> Float) = object : VectorConfig {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getOrDefault(property: VectorProperty<T>, defaultValue: T): T =
                when (property) {
                    VectorProperty.FillAlpha -> alpha() as T
                    else -> defaultValue
                }
        }
        val t = { progress.value }
        buildMap {
            put("ground", scaled { OnboardingMotion.groundScale(t()) })
            put("ground_fill", faded { OnboardingMotion.phase(t(), 0f, 0.12f) })
            put(
                "mark",
                scaled {
                    markScale * (0.82f + 0.18f * OnboardingMotion.easeOut(OnboardingMotion.phase(t(), 0.12f, 0.35f)))
                },
            )
            put("card", faded { OnboardingMotion.phase(t(), 0.15f, 0.2f) })
            put("band", faded { OnboardingMotion.phase(t(), 0.22f, 0.2f) })
            put("tab", faded { OnboardingMotion.phase(t(), 0.28f, 0.2f) })
            repeat(8) { i -> put("cell$i", scaled { OnboardingMotion.cellScale(t(), i) }) }
            put("today", scaled { OnboardingMotion.todayScale(t()) })
        }
    }
    val painter = rememberVectorPainter(
        defaultWidth = vector.defaultWidth,
        defaultHeight = vector.defaultHeight,
        viewportWidth = vector.viewportWidth,
        viewportHeight = vector.viewportHeight,
        name = vector.name,
        autoMirror = vector.autoMirror,
    ) { _, _ ->
        RenderVectorGroup(group = vector.root, configs = configs)
    }
    Image(painter = painter, contentDescription = null, modifier = modifier)
}

/**
 * Fades [content] in while it rises a few dp, starting [delayMillis] into the screen. The content
 * is laid out and interactive from the first frame; only its drawing is animated.
 */
@Composable
internal fun RiseIn(
    play: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(Motion.DurationLong + 120, delayMillis = delayMillis))
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            val p = OnboardingMotion.easeOut(progress.value)
            alpha = p
            translationY = (1f - p) * 14.dp.toPx()
        },
    ) {
        content()
    }
}

private const val GridRows = 5
private const val GridCols = 7
private const val TodayCell = 2 * GridCols + 4

/**
 * The waiting beat after a permission grant: a small month grid instead of a spinner.
 *
 * While the phone is being looked at a wave runs across it; once the calendar is set up the days
 * fill in and today's amber dot lands, the same dot the mark ends on.
 */
@Composable
internal fun CalendarScan(settled: Boolean, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val idle = MaterialTheme.colorScheme.surfaceContainerHigh
    val lit = MaterialTheme.colorScheme.primary
    val filled = lerp(idle, lit, 0.55f)
    val today = todayDiscColor()

    val wave = if (!settled && !reducedMotion) {
        rememberInfiniteTransition(label = "scan").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(OnboardingMotion.ScanPeriodMillis, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "scanWave",
        )
    } else {
        null
    }
    val settle = if (settled) rememberPlayOnce(!reducedMotion, OnboardingMotion.SettleMillis) else null

    Canvas(modifier = modifier.size(width = 134.dp, height = 94.dp)) {
        val gap = 6.dp.toPx()
        val cell = (size.width - gap * (GridCols - 1)) / GridCols
        val radius = CornerRadius(cell * 0.32f)
        val count = GridRows * GridCols
        for (row in 0 until GridRows) {
            for (col in 0 until GridCols) {
                val index = row * GridCols + col
                val origin = Offset(col * (cell + gap), row * (cell + gap))
                val center = origin + Offset(cell / 2, cell / 2)
                if (settle != null) {
                    val t = settle.value
                    if (index == TodayCell) {
                        val s = OnboardingMotion.todayScale(t)
                        drawRoundRect(idle, origin, Size(cell, cell), radius)
                        drawCircle(today, radius = cell / 2 * s, center = center)
                    } else {
                        val f = OnboardingMotion.settleFill(t, index, count)
                        drawRoundRect(lerp(idle, filled, f), origin, Size(cell, cell), radius)
                    }
                } else {
                    val v = wave?.value?.let {
                        OnboardingMotion.scanIntensity(it, row, col, GridRows, GridCols)
                    } ?: 0f
                    val grow = cell * 0.12f * v
                    drawRoundRect(
                        lerp(idle, lit, v),
                        origin - Offset(grow / 2, grow / 2),
                        Size(cell + grow, cell + grow),
                        radius,
                    )
                }
            }
        }
    }
}

/**
 * A small rendered week that shows what the theme and accent choices actually do, recoloured in
 * place as they change. The colours are animated here rather than across the whole screen: the
 * theme itself switches on the next frame, and this is the one place the eye is meant to be.
 */
@Composable
internal fun ThemePreview(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val spec = if (reducedMotion) snap() else tween<Color>(Motion.DurationLong + 140)
    val scheme = MaterialTheme.colorScheme
    val surface by animateColorAsState(scheme.surface, spec, label = "previewSurface")
    val onSurface by animateColorAsState(scheme.onSurface, spec, label = "previewInk")
    val muted by animateColorAsState(scheme.onSurfaceVariant, spec, label = "previewMuted")
    val line by animateColorAsState(scheme.outlineVariant, spec, label = "previewLine")
    val primary by animateColorAsState(scheme.primary, spec, label = "previewPrimary")
    val onPrimary by animateColorAsState(scheme.onPrimary, spec, label = "previewOnPrimary")
    val soft by animateColorAsState(scheme.primaryContainer, spec, label = "previewSoft")
    val weekend by animateColorAsState(weekendLabelColor(), spec, label = "previewWeekend")

    val locale = currentLocale()
    val now = remember { LocalDate.now() }
    val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val labels = remember(locale) { Dates.weekStartLabels(locale) }
    val selected = if (now.dayOfWeek == DayOfWeek.FRIDAY) 3 else 4

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                val day = monday.plusDays(i.toLong())
                val isToday = day == now
                val isSelected = i == selected && !isToday
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        labels[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i >= 5) weekend else muted,
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isToday -> todayDiscColor()
                                    isSelected -> primary
                                    else -> Color.Transparent
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            fontFamily = BricolageFamily,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isToday -> onTodayDiscColor()
                                isSelected -> onPrimary
                                else -> onSurface
                            },
                        )
                    }
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
        ) {
            val colWidth = size.width / 7
            val hour = size.height / 4
            for (h in 1 until 4) {
                drawLine(line, Offset(0f, hour * h), Offset(size.width, hour * h), 1.dp.toPx())
            }
            val inset = 2.dp.toPx()
            val r = CornerRadius(6.dp.toPx())
            // (column, start hour, length in hours, solid)
            listOf(
                listOf(0f, 0.2f, 1.4f, 1f),
                listOf(1f, 1.6f, 1.2f, 0f),
                listOf(2f, 0.5f, 2.2f, 1f),
                listOf(selected.toFloat(), 1.1f, 1.6f, 1f),
                listOf(5f, 0.3f, 1.0f, 0f),
                listOf(6f, 2.0f, 1.5f, 0f),
            ).forEach { (col, start, length, solid) ->
                drawRoundRect(
                    color = if (solid == 1f) primary else soft,
                    topLeft = Offset(col * colWidth + inset, start * hour + inset),
                    size = Size(colWidth - inset * 2, length * hour - inset * 2),
                    cornerRadius = r,
                )
            }
        }
    }
}
