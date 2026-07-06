package app.calendarium.ui.editor

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.model.Frequency
import app.calendarium.core.ui.theme.Motion
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val rowPadding = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorRoute(
    onBack: () -> Unit,
    viewModel: EventEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    state.scopePrompt?.let { prompt ->
        RecurrenceScopeDialog(
            prompt = prompt,
            onWholeSeries = { viewModel.resolveScope(wholeSeries = true) },
            onThisEvent = { viewModel.resolveScope(wholeSeries = false) },
            onDismiss = viewModel::dismissScopePrompt,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                label = { Text("Title") },
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
                            .menuAnchor(),
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
            ChipRow(
                title = "Repeats",
                selected = state.frequency,
                modifier = rowPadding.fillMaxWidth(),
            ) { freq -> viewModel.updateFrequency(freq) }
        }

        // Reminder
        Section {
            ReminderRow(
                selected = state.reminderMinutesBefore,
                onSelect = viewModel::updateReminder,
                modifier = rowPadding.fillMaxWidth(),
            )
        }

        // Location
        Section {
            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::updateLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                label = { Text("Location") },
                singleLine = true,
            )
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
    onWholeSeries: () -> Unit,
    onThisEvent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = if (prompt == RecurrenceScopePrompt.DELETE) "Delete" else "Change"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$verb recurring event") },
        text = { Text("This event repeats. Apply your change to just this occurrence or the whole series?") },
        confirmButton = {
            TextButton(onClick = onThisEvent) { Text("$verb this event") }
        },
        dismissButton = {
            TextButton(onClick = onWholeSeries) { Text("$verb all events") }
        },
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
            date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
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
                    time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())),
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
        TimePickerModal(initial = time, onDismiss = { showTimePicker = false }, onSelect = onPickTime)
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReminderRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        null to "None",
        0 to "At start",
        5 to "5 min",
        15 to "15 min",
        30 to "30 min",
        60 to "1 hour",
        1440 to "1 day",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reminder", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (minutes, label) ->
                AssistChip(
                    onClick = { onSelect(minutes) },
                    label = { Text(label) },
                    colors = if (selected == minutes) {
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
