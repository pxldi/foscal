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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.Attendee
import app.foscal.core.model.Frequency
import app.foscal.core.model.ReminderDuration
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.CalendarColors
import app.foscal.ui.common.ReminderDurationDialog
import app.foscal.ui.contrastColor
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.rememberDateFormatter
import app.foscal.ui.util.rememberTimeFormatter
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
            // Held here as a TextFieldValue rather than read straight off the state, because the
            // selection is part of what this field has to say: a new event opens with the title
            // selected so the first keystroke replaces whatever was pre-filled instead of landing
            // after it. The view model still owns the text.
            var titleField by remember {
                mutableStateOf(
                    TextFieldValue(state.title, TextRange(0, state.title.length)),
                )
            }
            val titleFocus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            // Only on a new event. Opening an existing one to change its time should not put a
            // keyboard over the form and the whole title under a selection one stray key erases.
            // The keyboard is asked for explicitly as well as implied by the focus: a request that
            // lands while the editor is still crossfading in is quietly dropped by the IME.
            LaunchedEffect(state.isEditing) {
                if (!state.isEditing) {
                    titleFocus.requestFocus()
                    keyboard?.show()
                }
            }
            OutlinedTextField(
                value = titleField,
                onValueChange = {
                    titleField = it
                    viewModel.updateTitle(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .focusRequester(titleFocus),
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

        // Colour — its own, or the calendar's.
        Section {
            ColorRow(
                selected = state.color,
                calendarColor = state.availableCalendars
                    .firstOrNull { it.id == state.selectedCalendarId }
                    ?.color,
                onSelect = viewModel::updateColor,
                modifier = rowPadding.fillMaxWidth(),
            )
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

        // Guests — hidden entirely on an event that is neither ours to invite to nor has anyone
        // on it, since there would be nothing to show and nothing to do.
        if (state.canEditGuests || state.attendees.isNotEmpty()) {
            Section {
                GuestsField(
                    attendees = state.attendees,
                    draft = state.guestDraft,
                    editable = state.canEditGuests,
                    canAdd = state.canAddGuest,
                    onDraftChange = viewModel::updateGuestDraft,
                    onAdd = viewModel::addGuest,
                    onRemove = viewModel::removeGuest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
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
                    time.format(rememberTimeFormatter()),
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/**
 * A colour for this one event, or the calendar's.
 *
 * The calendar's own colour leads and is what an event gets by default, because most events want
 * it: colouring by calendar is what makes a week readable at a glance, and an event that opts out
 * is saying something specific. The provider takes a free colour from an ordinary app, so the
 * swatches are the app's own palette rather than an account's — a sync adapter may still snap it
 * to whatever its server understands.
 */
@Composable
private fun ColorRow(
    selected: Int?,
    calendarColor: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Colour", style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            calendarColor?.let {
                EventColorSwatch(
                    colorArgb = it,
                    selected = selected == null,
                    contentDescription = "The calendar's colour",
                    onClick = { onSelect(null) },
                )
            }
            CalendarColors.presets.forEach { swatch ->
                EventColorSwatch(
                    colorArgb = swatch,
                    selected = selected == swatch,
                    contentDescription = null,
                    onClick = { onSelect(swatch) },
                )
            }
        }
    }
}

@Composable
private fun EventColorSwatch(
    colorArgb: Int,
    selected: Boolean,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription?.let { this.contentDescription = it } },
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

@Composable
private fun ReminderRow(
    selected: List<Int>,
    onToggle: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Show the presets plus any value the event already carries (a 10-minute alarm set in another
    // app must stay togglable here, or saving would silently drop it).
    val options = remember(selected) { (ReminderPresets + selected).distinct().sorted() }
    var picking by rememberSaveable { mutableStateOf(false) }

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
                    label = { Text(ReminderDuration.label(minutes)) },
                )
            }
            // Never "selected": anything it produces immediately shows up as its own chip above,
            // because `options` folds the event's current reminders in with the presets.
            FilterChip(
                selected = false,
                onClick = { picking = true },
                label = { Text("Custom…") },
            )
        }
    }

    if (picking) {
        ReminderDurationDialog(
            initialMinutes = selected.lastOrNull(),
            onDismiss = { picking = false },
            onConfirm = { minutes ->
                picking = false
                // A duplicate would toggle the existing chip *off*, so a user who re-picks a value
                // they already have would silently lose it.
                if (minutes !in selected) onToggle(minutes)
            },
        )
    }
}

/**
 * The guest list: one removable chip per attendee plus a field to add another.
 *
 * Foscal sends no invitations of its own — it writes the guests to the provider and the calendar's
 * sync adapter delivers them. The organizer's chip has no remove affordance: it names the event's
 * owner rather than someone who was invited.
 *
 * When [editable] is false the same guests are shown without the remove icons or the add field:
 * the event is one somebody else organized, so its guest list is theirs to change, but hiding it
 * outright would drop information the user can plainly see on the detail screen.
 */
@Composable
private fun GuestsField(
    attendees: List<Attendee>,
    draft: String,
    editable: Boolean,
    canAdd: Boolean,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Guests", style = MaterialTheme.typography.bodyLarge)
        if (attendees.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attendees.forEach { attendee ->
                    val removable = editable && !attendee.isOrganizer
                    InputChip(
                        selected = false,
                        enabled = removable,
                        onClick = { if (removable) onRemove(attendee.email) },
                        label = { Text(attendee.label) },
                        trailingIcon = if (removable) {
                            {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove ${attendee.label}",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        if (editable) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Add guest") },
                placeholder = { Text("name@example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                trailingIcon = {
                    IconButton(onClick = onAdd, enabled = canAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add guest")
                    }
                },
            )
        } else {
            Text(
                "Only the organizer can change who is invited.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
