package app.calendarium.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Calendar
import app.calendarium.core.model.ThemeMode
import app.calendarium.ui.calendars.CalendarsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CalendarsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Fully local · no account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Calendars come from your device and CalDAV synced by DAVx₅. Nothing leaves your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Appearance")
            }
            item {
                AccentPicker(
                    selected = state.accentColor,
                    customColor = state.accentCustomColor,
                    onSelectPreset = { viewModel.setAccentColor(it) },
                    onPickCustom = { viewModel.setCustomAccentColor(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                ThemeModePicker(
                    selected = state.themeMode,
                    onSelect = { viewModel.setThemeMode(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Date & time")
            }
            item {
                ToggleRow(
                    title = "24-hour time",
                    subtitle = if (state.use24HourClock) "13:00" else "1:00 PM",
                    checked = state.use24HourClock,
                    onToggle = { viewModel.setUse24HourClock(it) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Location")
            }
            item {
                ToggleRow(
                    title = "Pick locations on a map",
                    subtitle = if (state.osmMapsEnabled) {
                        "On · uses OpenStreetMap (contacts the network)"
                    } else {
                        "Off · location entry stays fully offline"
                    },
                    checked = state.osmMapsEnabled,
                    onToggle = { viewModel.setOsmMapsEnabled(it) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Calendars")
            }
            items(state.items, key = { it.calendar.id }) { row ->
                CalendarRowCard(
                    calendar = row.calendar,
                    isHidden = row.isHidden,
                    onToggle = { viewModel.toggleHidden(row) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Notifications")
            }
            item {
                NotificationSettings(
                    selected = state.defaultReminderMinutes,
                    onSelect = { viewModel.setDefaultReminder(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Text(
                    "Calendarium does not run its own sync service. DAVx₅ or your account app keeps CalDAV calendars current.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun CalendarRowCard(
    calendar: Calendar,
    isHidden: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ColorDot(calendar.color)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    calendar.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    accountLabel(calendar),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = !isHidden, onCheckedChange = { onToggle() })
        }
    }
}

private fun accountLabel(calendar: Calendar): String {
    val type = if (calendar.isLocal) "Local" else calendar.accountType
    return "${calendar.accountName} • $type"
}

@Composable
private fun ColorDot(colorArgb: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(colorArgb)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Theme",
            style = MaterialTheme.typography.bodyMedium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationSettings(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        null to "None",
        5 to "5 min before",
        15 to "15 min before",
        30 to "30 min before",
        60 to "1 hour before",
        1440 to "1 day before",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Default reminder for new events",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (mins, label) ->
                AssistChip(
                    onClick = { onSelect(mins) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    colors = if (selected == mins) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }
    }
}
