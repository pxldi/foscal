package app.foscal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foscal.core.data.Preferences
import app.foscal.core.model.EventColorStrength
import app.foscal.ui.CalendarColors
import app.foscal.ui.eventColors
import app.foscal.ui.util.LocalEventColorStrength
import app.foscal.ui.util.LocalEventTextScale
import app.foscal.ui.util.LocalWrapEventTitles
import app.foscal.ui.util.scaledBy
import kotlin.math.roundToInt

/**
 * How events are drawn, with a sample of the grid above the controls.
 *
 * The sample is not a picture: it is the same [eventColors] helper and the same ambient text scale
 * the real week grid reads, so it cannot drift from what changing a control actually does. Every
 * one of these is a matter of taste that the app has no way to settle on the user's behalf — how
 * loud a full calendar is allowed to be, and how much of a title is worth having over how legibly.
 */
@Composable
fun CalendarStyleSettings(
    state: EventStyleState,
    viewModel: EventStyleViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StylePreview(modifier = Modifier.padding(horizontal = 12.dp))

        StyleHeader("Colours")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EventColorStrength.entries.forEach { strength ->
                StrengthSwatch(
                    strength = strength,
                    selected = strength == state.colorStrength,
                    onSelect = { viewModel.setColorStrength(strength) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        StyleHeader("Title size")
        TextScaleSlider(
            percent = state.textScalePercent,
            onPercent = viewModel::setTextScalePercent,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        ToggleRow(
            title = "Wrap event titles",
            subtitle = if (state.wrapTitles) {
                "Long titles run onto a second line"
            } else {
                "One line, cut off where it runs out"
            },
            checked = state.wrapTitles,
            onToggle = viewModel::setWrapTitles,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StyleHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** Three days of a made-up week, drawn the way the real grid would draw them. */
@Composable
private fun StylePreview(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .height(PreviewHourHeight * PreviewHours),
        ) {
            Column(modifier = Modifier.padding(end = 6.dp)) {
                for (hour in 0 until PreviewHours) {
                    Box(Modifier.height(PreviewHourHeight), contentAlignment = Alignment.TopEnd) {
                        Text(
                            "%02d:00".format(PreviewFirstHour + hour),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            (0 until PreviewColumns).forEach { column ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp),
                ) {
                    for (hour in 1 until PreviewHours) {
                        HorizontalDivider(
                            modifier = Modifier.offset(y = PreviewHourHeight * hour),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    PreviewSamples.filter { it.column == column }.forEach { sample ->
                        PreviewBlock(
                            sample = sample,
                            modifier = Modifier
                                .offset(y = PreviewHourHeight * (sample.startMinute / 60f))
                                .fillMaxWidth()
                                .height(
                                    PreviewHourHeight *
                                        ((sample.endMinute - sample.startMinute) / 60f) - 2.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBlock(sample: PreviewSample, modifier: Modifier) {
    val colors = eventColors(CalendarColors.pick(sample.colorIndex))
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.container)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            sample.title,
            color = colors.content,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp.scaledBy(LocalEventTextScale.current),
            maxLines = if (LocalWrapEventTitles.current) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One of the four strengths, shown as the fills it produces rather than named alone.
 *
 * The bars are drawn with the strength this swatch stands for provided over the ambient one, so
 * the four sit side by side as the choice they actually are.
 */
@Composable
private fun StrengthSwatch(
    strength: EventColorStrength,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompositionLocalProvider(LocalEventColorStrength provides strength) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                SwatchColorIndices.forEach { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .background(eventColors(CalendarColors.pick(index)).container),
                    )
                }
            }
        }
        Text(
            strength.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        RadioButton(selected = selected, onClick = onSelect)
    }
}

@Composable
private fun TextScaleSlider(
    percent: Int,
    onPercent: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = Preferences.EVENT_TEXT_SCALES
    // Nearest rather than exact: a value stored by another build need not be one of these stops,
    // and a slider that renders at zero because it did not recognise the number is worse than one
    // that rounds to the neighbour.
    val index = steps.indices.minBy { kotlin.math.abs(steps[it] - percent) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("A", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = index.toFloat(),
            onValueChange = { raw ->
                val next = steps[raw.roundToInt().coerceIn(steps.indices)]
                if (next != percent) onPercent(next)
            },
            valueRange = 0f..(steps.size - 1).toFloat(),
            // One fewer than the gaps between stops: the ends are not "steps" to Material.
            steps = steps.size - 2,
            modifier = Modifier.weight(1f),
        )
        Text("A", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

private data class PreviewSample(
    val title: String,
    val column: Int,
    val startMinute: Int,
    val endMinute: Int,
    val colorIndex: Int,
)

private const val PreviewColumns = 3
private const val PreviewHours = 3
private const val PreviewFirstHour = 9
private val PreviewHourHeight: Dp = 46.dp

/** Deliberately includes a title too long for its column, since that is what wrapping decides. */
private val PreviewSamples = listOf(
    PreviewSample("Standup", column = 0, startMinute = 5, endMinute = 45, colorIndex = 0),
    PreviewSample("Quarterly planning workshop", column = 0, startMinute = 55, endMinute = 155, colorIndex = 4),
    PreviewSample("Design review", column = 1, startMinute = 0, endMinute = 95, colorIndex = 2),
    PreviewSample("Gym", column = 1, startMinute = 110, endMinute = 175, colorIndex = 5),
    PreviewSample("Lunch with Anna", column = 2, startMinute = 60, endMinute = 140, colorIndex = 3),
)

/** Four of the eight calendar presets, enough to show what a strength does to a mixed week. */
private val SwatchColorIndices = listOf(2, 3, 0, 1)
