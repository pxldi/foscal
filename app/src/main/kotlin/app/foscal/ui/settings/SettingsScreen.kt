package app.foscal.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.BuildConfig
import app.foscal.core.model.Calendar
import app.foscal.core.model.Ics
import app.foscal.core.model.ReminderDuration
import app.foscal.core.model.ThemeMode
import app.foscal.ics.IcsTransfer
import app.foscal.ui.CalendarColors
import app.foscal.ui.calendars.CalendarRow
import app.foscal.ui.calendars.CalendarsUiState
import app.foscal.ui.calendars.CalendarsViewModel
import app.foscal.ui.calendars.TransferState
import app.foscal.ui.common.ReminderDurationDialog
import app.foscal.ui.contrastColor
import app.foscal.ui.home.BehaviourViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    section: SettingsSection?,
    onOpenSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    viewModel: CalendarsViewModel = hiltViewModel(),
    behaviourViewModel: BehaviourViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val behaviour by behaviourViewModel.state.collectAsStateWithLifecycle()
    // One calendar open at a time: the per-calendar panel is tall, and several expanded at once
    // turns the list into something you have to scroll to find anything in.
    var expandedCalendarId by rememberSaveable { mutableStateOf<Long?>(null) }
    var addingCalendar by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        section?.title ?: "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
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
            when (section) {
                null -> settingsIndex(onOpenSection)
                SettingsSection.Appearance -> appearanceSection(state, viewModel)
                SettingsSection.CalendarView -> item {
                    CalendarViewSettings(
                        state = behaviour,
                        viewModel = behaviourViewModel,
                        use24HourClock = state.use24HourClock,
                        onUse24HourClock = viewModel::setUse24HourClock,
                    )
                }
                SettingsSection.NewEvents -> item {
                    NewEventSettings(
                        state = behaviour,
                        viewModel = behaviourViewModel,
                        calendars = state.items,
                        mapsEnabled = state.osmMapsEnabled,
                        onMapsEnabled = viewModel::setOsmMapsEnabled,
                    )
                }
                SettingsSection.Calendars -> calendarsSection(
                    state = state,
                    viewModel = viewModel,
                    expandedCalendarId = expandedCalendarId,
                    onExpand = { id -> expandedCalendarId = if (expandedCalendarId == id) null else id },
                    onAddCalendar = { addingCalendar = true },
                )
                SettingsSection.Reminders -> remindersSection(state, viewModel)
                SettingsSection.Transfer -> item {
                    ImportExportSection(
                        calendars = state.items.map { it.calendar },
                        transfer = state.transfer,
                        onExport = { viewModel.exportTo(it) },
                        onImport = { uri, calendarId -> viewModel.importFrom(uri, calendarId) },
                        onDismissMessage = { viewModel.dismissTransferMessage() },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                SettingsSection.About -> item { AboutSection() }
            }
        }

        if (addingCalendar) {
            AddCalendarDialog(
                error = state.createError,
                onDismiss = {
                    addingCalendar = false
                    viewModel.dismissCreateError()
                },
                onCreate = { name, color ->
                    viewModel.createCalendar(name, color)
                    addingCalendar = false
                },
            )
        }
    }
}

private fun LazyListScope.settingsIndex(onOpen: (SettingsSection) -> Unit) {
    items(SettingsSection.entries, key = { it.name }) { section ->
        SectionRow(section = section, onClick = { onOpen(section) })
    }
}

@Composable
private fun SectionRow(section: SettingsSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(section.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                section.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListScope.appearanceSection(
    state: CalendarsUiState,
    viewModel: CalendarsViewModel,
) {
    // Material You needs a wallpaper-derived palette the platform only exposes from Android 12
    // on, so on anything older the toggle would be a switch that cannot do anything and is left
    // out entirely.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item {
            ToggleRow(
                title = "Use wallpaper colours",
                subtitle = if (state.dynamicColor) "On" else "Off",
                checked = state.dynamicColor,
                onToggle = { viewModel.setDynamicColor(it) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    // The accent is what wallpaper colours replace, so showing the picker alongside them would
    // offer a choice that changes nothing on screen.
    if (!state.dynamicColor) {
        item {
            AccentPicker(
                selected = state.accentColor,
                customColor = state.accentCustomColor,
                onSelectPreset = { viewModel.setAccentColor(it) },
                onPickCustom = { viewModel.setCustomAccentColor(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    item {
        ThemeModePicker(
            selected = state.themeMode,
            onSelect = { viewModel.setThemeMode(it) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun LazyListScope.calendarsSection(
    state: CalendarsUiState,
    viewModel: CalendarsViewModel,
    expandedCalendarId: Long?,
    onExpand: (Long) -> Unit,
    onAddCalendar: () -> Unit,
) {
    item {
        Text(
            "Tap a calendar to give it its own reminder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
    }
    items(state.items, key = { it.calendar.id }) { row ->
        CalendarRowCard(
            row = row,
            globalReminderMinutes = state.defaultReminderMinutes,
            expanded = expandedCalendarId == row.calendar.id,
            onExpand = { onExpand(row.calendar.id) },
            onToggleHidden = { viewModel.toggleHidden(row) },
            onSelectReminder = { selection ->
                when (selection) {
                    ReminderSelection.Global -> viewModel.clearCalendarReminder(row.calendar.id)
                    ReminderSelection.None -> viewModel.setCalendarReminder(row.calendar.id, null)
                    is ReminderSelection.Minutes ->
                        viewModel.setCalendarReminder(row.calendar.id, selection.value)
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    item {
        ActionRow(
            title = "Add a calendar",
            subtitle = "Kept on this phone",
            icon = Icons.Outlined.Add,
            enabled = true,
            onClick = onAddCalendar,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

private fun LazyListScope.remindersSection(
    state: CalendarsUiState,
    viewModel: CalendarsViewModel,
) {
    item {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Default reminder", style = MaterialTheme.typography.bodyMedium)
            ReminderChips(
                selection = state.defaultReminderMinutes
                    ?.let { ReminderSelection.Minutes(it) }
                    ?: ReminderSelection.None,
                // Nothing above the app-wide default to fall back to.
                globalLabel = null,
                onSelect = { selection ->
                    viewModel.setDefaultReminder(
                        (selection as? ReminderSelection.Minutes)?.value,
                    )
                },
            )
        }
    }
    item { ReminderDiagnosticsCard(modifier = Modifier.padding(horizontal = 12.dp)) }
}

@Composable
private fun AboutSection() {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Foscal ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Foscal doesn't sync by itself. DAVx\u2085 or your account app keeps calendars current.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * One calendar: visible/hidden, and — when expanded — the reminder new events on it start with.
 *
 * The row used to toggle visibility on tap. That is now the switch's job alone, because a row that
 * both expands and toggles has no way to be tapped for one without doing the other.
 */
@Composable
private fun CalendarRowCard(
    row: CalendarRow,
    globalReminderMinutes: Int?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onToggleHidden: () -> Unit,
    onSelectReminder: (ReminderSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val calendar = row.calendar
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ColorDot(calendar.color)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        calendar.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(accountLabel(calendar))
                            if (!row.usesGlobalReminder) {
                                append(" • ")
                                append(row.reminderOverride?.let(ReminderDuration::label) ?: "No reminder")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = !row.isHidden, onCheckedChange = { onToggleHidden() })
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider()
                    Text(
                        "Reminder for new events here",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    ReminderChips(
                        selection = when {
                            row.usesGlobalReminder -> ReminderSelection.Global
                            row.reminderOverride == null -> ReminderSelection.None
                            else -> ReminderSelection.Minutes(row.reminderOverride)
                        },
                        globalLabel = globalReminderMinutes
                            ?.let { "Default · ${ReminderDuration.label(it)}" }
                            ?: "Default · none",
                        onSelect = onSelectReminder,
                    )
                }
            }
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

/**
 * Name and colour for a calendar to be created on this device.
 *
 * Two fields and nothing else on purpose. Everything else a calendar row carries — account, sync
 * setting, time zone — has exactly one possible answer for a calendar that lives only here, so
 * asking would turn a two-second action into a form.
 *
 * The note about syncing is there because this is where someone comes looking to add their work
 * calendar, and the honest answer is that no app can create that one for them: it is made on the
 * server, and DAVx5 or the account's own app brings it down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCalendarDialog(
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (String, Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(CalendarColors.pick(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CalendarColors.presets.forEach { swatch ->
                        ColorSwatch(
                            colorArgb = swatch,
                            selected = swatch == color,
                            onClick = { color = swatch },
                        )
                    }
                }
                Text(
                    "Kept on this phone. Calendars that sync are made where they sync from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, color) },
                enabled = name.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColorSwatch(colorArgb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = contrastColor(colorArgb),
                modifier = Modifier.size(20.dp),
            )
        }
    }
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

/**
 * `.ics` import and export, both driven by the Storage Access Framework so the app needs no
 * storage permission and can only touch the one document the user picks.
 */
@Composable
private fun ImportExportSection(
    calendars: List<Calendar>,
    transfer: TransferState,
    onExport: (Uri) -> Unit,
    onImport: (Uri, Long) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingCalendar by remember { mutableStateOf(false) }
    var importTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Ics.MIME_TYPE),
    ) { uri -> uri?.let(onExport) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val calendarId = importTarget
        if (uri != null && calendarId != null) onImport(uri, calendarId)
        importTarget = null
    }

    val exportName = remember { IcsTransfer.defaultExportName() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionRow(
            title = "Export to .ics",
            subtitle = "Every event on your visible calendars",
            icon = Icons.Outlined.FileUpload,
            enabled = !transfer.busy,
            onClick = {
                onDismissMessage()
                exportLauncher.launch(exportName)
            },
        )
        ActionRow(
            title = "Import from .ics",
            subtitle = "Adds a file's events to a calendar you pick",
            icon = Icons.Outlined.FileDownload,
            enabled = !transfer.busy && calendars.isNotEmpty(),
            onClick = {
                onDismissMessage()
                pickingCalendar = true
            },
        )

        if (transfer.busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        transfer.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (transfer.failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (pickingCalendar) {
        AlertDialog(
            onDismissRequest = { pickingCalendar = false },
            title = { Text("Import into") },
            text = {
                Column {
                    calendars.forEach { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingCalendar = false
                                    // Held across the picker: launching a document intent can let
                                    // the system stop this process, and the callback needs to know
                                    // which calendar the user chose when it comes back.
                                    importTarget = calendar.id
                                    importLauncher.launch(IMPORT_MIME_TYPES)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ColorDot(calendar.color)
                            Text(
                                calendar.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingCalendar = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * `.ics` files are routinely served with a generic type — some document providers report
 * `application/octet-stream` for anything they do not recognise — so filtering on `text/calendar`
 * alone greys out the very file the user came to pick.
 */
private val IMPORT_MIME_TYPES = arrayOf(
    Ics.MIME_TYPE,
    "text/x-vcalendar",
    "text/plain",
    "application/octet-stream",
)

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
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
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val contentAlpha = if (enabled) 1f else 0.38f
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
    }
}

@Composable
internal fun ToggleRow(
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

/** What a reminder chip row can be set to. */
private sealed interface ReminderSelection {
    /** Follow the app-wide default. Only offered on a per-calendar row. */
    data object Global : ReminderSelection

    /** No reminder at all — distinct from [Global] even when the global default is itself none. */
    data object None : ReminderSelection

    data class Minutes(val value: Int) : ReminderSelection
}

private val ReminderPresetMinutes = listOf(5, 15, 30, 60, 1440)

/**
 * The reminder picker shared by the app-wide default and each calendar's override.
 *
 * [globalLabel] non-null adds the leading "follow the default" chip; the app-wide row passes null
 * because there is nothing above it to defer to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderChips(
    selection: ReminderSelection,
    globalLabel: String?,
    onSelect: (ReminderSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = (selection as? ReminderSelection.Minutes)?.value
    // Fold a custom value in with the presets so it shows as its own selected chip rather than
    // leaving the row looking as though nothing is chosen.
    val options = remember(current) {
        (ReminderPresetMinutes + listOfNotNull(current)).distinct().sorted()
    }
    var picking by rememberSaveable { mutableStateOf(false) }

    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (globalLabel != null) {
            ReminderChip(
                label = globalLabel,
                selected = selection == ReminderSelection.Global,
                onClick = { onSelect(ReminderSelection.Global) },
            )
        }
        ReminderChip(
            label = "None",
            selected = selection == ReminderSelection.None,
            onClick = { onSelect(ReminderSelection.None) },
        )
        options.forEach { minutes ->
            ReminderChip(
                label = ReminderDuration.label(minutes),
                selected = current == minutes,
                onClick = { onSelect(ReminderSelection.Minutes(minutes)) },
            )
        }
        ReminderChip(
            label = "Custom…",
            selected = false,
            onClick = { picking = true },
        )
    }

    if (picking) {
        ReminderDurationDialog(
            initialMinutes = current,
            onDismiss = { picking = false },
            onConfirm = { minutes ->
                picking = false
                onSelect(ReminderSelection.Minutes(minutes))
            },
        )
    }
}

@Composable
private fun ReminderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = if (selected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}
