package app.foscal.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Frequency
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.rememberDateFormatter
import app.foscal.ui.util.timeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle

private val rowPadding = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorRoute(
    onBack: () -> Unit,
    onPickLocation: (currentQuery: String) -> Unit = {},
    pickedLocation: String? = null,
    onPickedLocationConsumed: () -> Unit = {},
    viewModel: EventEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    // A place chosen on the map picker comes back through the nav back-stack; apply it once.
    LaunchedEffect(pickedLocation) {
        pickedLocation?.let {
            viewModel.updateLocation(it)
            onPickedLocationConsumed()
        }
    }

    state.scopePrompt?.let { prompt ->
        RecurrenceScopeDialog(
            prompt = prompt,
            onScope = viewModel::resolveScope,
            onDismiss = viewModel::dismissScopePrompt,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text(if (state.isEditing) "Edit event" else "New event") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        Crossfade(
            targetState = state.loading,
            animationSpec = tween(Motion.DurationMedium),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "editorCrossfade",
        ) { loading ->
            if (loading) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                EditorForm(
                    state = state,
                    viewModel = viewModel,
                    onPickLocation = onPickLocation,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorForm(
    state: EditorUiState,
    viewModel: EventEditorViewModel,
    onPickLocation: (currentQuery: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Title
        Section {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::updateTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                placeholder = { Text("Add title") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
            )
        }

        // Calendar picker
        if (state.availableCalendars.isNotEmpty()) {
            Section {
                var expanded by remember { mutableStateOf(false) }
                val selected = state.availableCalendars.firstOrNull { it.id == state.selectedCalendarId }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    OutlinedTextField(
                        value = selected?.displayName ?: "Select calendar",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Calendar") },
                        leadingIcon = {
                            ColorDot(Modifier.size(14.dp), color = selected?.color ?: 0xFF1976D2.toInt())
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        state.availableCalendars.forEach { cal ->
                            DropdownMenuItem(
                                text = { Text(cal.displayName) },
                                leadingIcon = { ColorDot(Modifier.size(14.dp), color = cal.color) },
                                onClick = {
                                    viewModel.selectCalendar(cal.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // All-day toggle
        Section {
            ToggleRow(
                label = "All day",
                checked = state.allDay,
                onCheckedChange = viewModel::updateAllDay,
                modifier = rowPadding.fillMaxWidth(),
            )
        }

        // Start / End
        Section {
            DateTimeRow(
                label = "Starts",
                date = state.startDate,
                time = state.startTime,
                showTime = !state.allDay,
                onPickDate = viewModel::updateStartDate,
                onPickTime = viewModel::updateStartTime,
                modifier = rowPadding.fillMaxWidth(),
            )
            DateTimeRow(
                label = "Ends",
                date = state.endDate,
                time = state.endTime,
                showTime = !state.allDay,
                onPickDate = viewModel::updateEndDate,
                onPickTime = viewModel::updateEndTime,
                modifier = rowPadding.fillMaxWidth(),
            )
        }

        // Recurrence
        Section {
            Column(
                modifier = rowPadding.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChipRow(
                    title = "Repeats",
                    selected = state.frequency,
                ) { freq -> viewModel.updateFrequency(freq) }
                if (state.frequency != Frequency.NONE) {
                    TextButton(onClick = viewModel::toggleCustomRecurrence) {
                        Text(if (state.showCustomRecurrence) "Hide options" else "Customize…")
                    }
                    if (state.showCustomRecurrence) {
                        CustomRecurrenceControls(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // Reminder
        Section {
            ReminderRow(
                selected = state.reminderMinutes,
                onToggle = viewModel::toggleReminder,
                modifier = rowPadding.fillMaxWidth(),
            )
        }

        // Location — free text with offline autocomplete over the user's own past locations.
        Section {
            val suggestions = remember(state.location, state.recentLocations) {
                val query = state.location.trim()
                state.recentLocations
                    .filter { it != state.location && (query.isEmpty() || it.contains(query, ignoreCase = true)) }
                    .take(6)
            }
            var expanded by remember { mutableStateOf(false) }
            val menuOpen = expanded && suggestions.isNotEmpty()
            ExposedDropdownMenuBox(
                expanded = menuOpen,
                onExpandedChange = { expanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                OutlinedTextField(
                    value = state.location,
                    onValueChange = {
                        viewModel.updateLocation(it)
                        expanded = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    label = { Text("Location") },
                    singleLine = true,
                    trailingIcon = if (suggestions.isNotEmpty()) {
                        { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) }
                    } else {
                        null
                    },
                )
                ExposedDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { expanded = false },
                ) {
                    suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            leadingIcon = {
                                Icon(Icons.Outlined.LocationOn, contentDescription = null)
                            },
                            onClick = {
                                viewModel.updateLocation(suggestion)
                                expanded = false
                            },
                        )
                    }
                }
            }
            if (state.mapsEnabled) {
                TextButton(
                    onClick = { onPickLocation(state.location) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Pick on map")
                }
            }
        }

        // Notes
        Section {
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::updateDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 6,
            )
        }

        AnimatedVisibility(
            visible = state.isEditing,
            enter = fadeIn(tween(Motion.DurationMedium)),
            exit = fadeOut(tween(Motion.DurationMedium)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Section {
                    TextButton(
                        onClick = viewModel::delete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Delete event")
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun RecurrenceScopeDialog(
    prompt: RecurrenceScopePrompt,
    onScope: (RecurrenceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = if (prompt == RecurrenceScopePrompt.DELETE) "Delete" else "Change"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb recurring event") },
        text = {
            Column {
                Text("This event repeats. Apply your change to:")
                Spacer(Modifier.height(16.dp))
                ScopeChoice("$verb this event") { onScope(RecurrenceScope.SINGLE) }
                ScopeChoice("$verb this and following events") { onScope(RecurrenceScope.THIS_AND_FOLLOWING) }
                ScopeChoice("$verb all events") { onScope(RecurrenceScope.ALL_EVENTS) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ScopeChoice(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column {
        content()
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun ColorDot(modifier: Modifier = Modifier, color: Int) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(color)),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: LocalTime,
    showTime: Boolean,
    onPickDate: (LocalDate) -> Unit,
    onPickTime: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            date.format(rememberDateFormatter("EEE, MMM d")),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showDatePicker = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        AnimatedVisibility(
            visible = showTime,
            enter = expandHorizontally(
                animationSpec = tween(Motion.DurationMedium),
                expandFrom = Alignment.Start,
            ) + fadeIn(tween(Motion.DurationMedium)),
            exit = shrinkHorizontally(
                animationSpec = tween(Motion.DurationMedium),
                shrinkTowards = Alignment.Start,
            ) + fadeOut(tween(Motion.DurationMedium)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp))
                Text(
                    time.format(timeFormatter(LocalUse24HourClock.current)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showTimePicker = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (showDatePicker) {
        DatePickerModal(initial = date, onDismiss = { showDatePicker = false }, onSelect = onPickDate)
    }
    if (showTimePicker) {
        TimePickerModal(
            initial = time,
            is24Hour = LocalUse24HourClock.current,
            onDismiss = { showTimePicker = false },
            onSelect = onPickTime,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    title: String,
    selected: Frequency,
    modifier: Modifier = Modifier,
    onSelect: (Frequency) -> Unit,
) {
    val options = listOf(
        Frequency.NONE to "Once",
        Frequency.DAILY to "Daily",
        Frequency.WEEKLY to "Weekly",
        Frequency.MONTHLY to "Monthly",
        Frequency.YEARLY to "Yearly",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (freq, label) ->
                AssistChip(
                    onClick = { onSelect(freq) },
                    label = { Text(label) },
                    colors = if (selected == freq) {
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

private val ReminderPresets = listOf(0, 5, 15, 30, 60, 1440)

internal fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> "At start"
    minutes % 1440 == 0 -> "${minutes / 1440} day".pluralize(minutes / 1440)
    minutes % 60 == 0 -> "${minutes / 60} hour".pluralize(minutes / 60)
    else -> "$minutes min"
}

private fun String.pluralize(count: Int): String = if (count == 1) this else "${this}s"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReminderRow(
    selected: List<Int>,
    onToggle: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Show the presets plus any value the event already carries (a 10-minute alarm set in another
    // app must stay togglable here, or saving would silently drop it).
    val options = remember(selected) { (ReminderPresets + selected).distinct().sorted() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reminders", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onToggle(null) },
                label = { Text("None") },
            )
            options.forEach { minutes ->
                FilterChip(
                    selected = minutes in selected,
                    onClick = { onToggle(minutes) },
                    label = { Text(reminderLabel(minutes)) },
                )
            }
        }
    }
}

@Composable
private fun CustomRecurrenceControls(
    state: EditorUiState,
    viewModel: EventEditorViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntervalRow(
            interval = state.interval,
            unit = unitLabel(state.frequency, state.interval),
            onDecrement = { viewModel.updateInterval(state.interval - 1) },
            onIncrement = { viewModel.updateInterval(state.interval + 1) },
        )
        EndRow(state = state, viewModel = viewModel)
        if (state.frequency == Frequency.WEEKLY) {
            ByWeekdayRow(byWeekday = state.byWeekday, onToggle = viewModel::toggleByWeekday)
        }
    }
}

private fun unitLabel(frequency: Frequency, interval: Int): String {
    val singular = when (frequency) {
        Frequency.DAILY -> "day"
        Frequency.WEEKLY -> "week"
        Frequency.MONTHLY -> "month"
        Frequency.YEARLY -> "year"
        Frequency.NONE -> ""
    }
    return if (interval == 1) singular else singular + "s"
}

@Composable
private fun IntervalRow(
    interval: Int,
    unit: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Repeat every", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Stepper(
            value = interval,
            onDecrement = onDecrement,
            onIncrement = onIncrement,
            decrementEnabled = interval > 1,
        )
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Stepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementEnabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onDecrement, enabled = decrementEnabled) {
            Icon(Icons.Outlined.Remove, contentDescription = "Less")
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(min = 24.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onIncrement) {
            Icon(Icons.Outlined.Add, contentDescription = "More")
        }
    }
}

private enum class EndMode { FOREVER, UNTIL, COUNT }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EndRow(state: EditorUiState, viewModel: EventEditorViewModel) {
    val mode = when {
        state.recurrenceCount != null -> EndMode.COUNT
        state.recurrenceEndDate != null -> EndMode.UNTIL
        else -> EndMode.FOREVER
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ends", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = mode == EndMode.FOREVER,
                onClick = {
                    viewModel.updateRecurrenceCount(null)
                    viewModel.updateRecurrenceEndDate(null)
                },
                label = { Text("Forever") },
            )
            FilterChip(
                selected = mode == EndMode.UNTIL,
                onClick = {
                    viewModel.updateRecurrenceEndDate(
                        state.recurrenceEndDate ?: state.startDate.plusMonths(1),
                    )
                },
                label = { Text("On date") },
            )
            FilterChip(
                selected = mode == EndMode.COUNT,
                onClick = { viewModel.updateRecurrenceCount(state.recurrenceCount ?: 10) },
                label = { Text("After") },
            )
        }
        when (mode) {
            EndMode.UNTIL -> EndDateRow(
                date = state.recurrenceEndDate,
                onPick = viewModel::updateRecurrenceEndDate,
            )
            EndMode.COUNT -> {
                val count = state.recurrenceCount ?: 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Occurrences",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Stepper(
                        value = count,
                        onDecrement = { viewModel.updateRecurrenceCount(count - 1) },
                        onIncrement = { viewModel.updateRecurrenceCount(count + 1) },
                        decrementEnabled = count > 1,
                    )
                }
            }
            EndMode.FOREVER -> {}
        }
    }
}

@Composable
private fun EndDateRow(date: LocalDate?, onPick: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Date", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            date?.format(rememberDateFormatter("EEE, MMM d, yyyy")) ?: "Pick date",
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showPicker = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (showPicker) {
        DatePickerModal(
            initial = date ?: LocalDate.now(),
            onDismiss = { showPicker = false },
            onSelect = {
                onPick(it)
                showPicker = false
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ByWeekdayRow(byWeekday: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
            ).forEach { day ->
                FilterChip(
                    selected = day in byWeekday,
                    onClick = { onToggle(day) },
                    label = {
                        Text(day.getDisplayName(TextStyle.NARROW, locale))
                    },
                )
            }
        }
    }
}
