package app.foscal.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.foscal.core.model.AccentColor
import app.foscal.core.ui.theme.LocalIsDarkTheme
import app.foscal.core.ui.theme.tokens

/**
 * Accent selector shared by Settings and onboarding: the three presets plus a "Custom" swatch that
 * opens a color picker. Selecting a preset calls [onSelectPreset]; confirming the picker calls
 * [onPickCustom] with the chosen ARGB.
 */
@Composable
fun AccentPicker(
    selected: AccentColor,
    customColor: Int,
    onSelectPreset: (AccentColor) -> Unit,
    onPickCustom: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Null where the surrounding card already names the setting, so it isn't labelled twice.
    label: String? = "Accent color",
) {
    val dark = LocalIsDarkTheme.current
    var showPicker by remember { mutableStateOf(false) }
    val presets = listOf(
        AccentColor.COBALT to "Cobalt",
        AccentColor.VIOLET to "Violet",
        AccentColor.FOREST to "Forest",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            presets.forEach { (accent, label) ->
                val tokens = accent.tokens()
                AccentSwatch(
                    color = if (dark) tokens.primaryDark else tokens.primaryLight,
                    label = label,
                    selected = accent == selected,
                    onClick = { onSelectPreset(accent) },
                )
            }
            AccentSwatch(
                color = Color(customColor),
                label = "Custom",
                selected = selected == AccentColor.CUSTOM,
                rainbowRing = true,
                onClick = { showPicker = true },
            )
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initialColor = customColor,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                onPickCustom(it)
            },
        )
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    rainbowRing: Boolean = false,
) {
    val rainbow = Brush.sweepGradient(
        listOf(
            Color(0xFFE53935), Color(0xFFFFB300), Color(0xFF43A047),
            Color(0xFF00ACC1), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935),
        ),
    )
    Column(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .size(width = 60.dp, height = 78.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    when {
                        selected -> Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        rainbowRing -> Modifier.border(2.dp, rainbow, CircleShape)
                        else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}
