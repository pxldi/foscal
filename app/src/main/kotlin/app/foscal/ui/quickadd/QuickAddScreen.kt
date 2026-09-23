package app.foscal.ui.quickadd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.model.QuickAddParser
import app.foscal.ui.feedback.FeedbackSnackbarHost
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.rememberDateFormatter
import app.foscal.ui.util.rememberTimeFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddRoute(
    onBack: () -> Unit,
    viewModel: QuickAddViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    Scaffold(
        snackbarHost = { FeedbackSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Quick add") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            // The app draws edge to edge, so the window is not resized for the keyboard. Without the
            // IME padding the keyboard covered "Add event".
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                QuickAddForm(
                    state = state,
                    onQueryChange = viewModel::updateQuery,
                    onSelectCalendar = viewModel::selectCalendar,
                    onSave = { use24Hour -> viewModel.save(use24Hour) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddForm(
    state: QuickAddUiState,
    onQueryChange: (String) -> Unit,
    onSelectCalendar: (Long) -> Unit,
    onSave: (use24Hour: Boolean) -> Unit,
) {
    val use24Hour = LocalUse24HourClock.current
    val parsed = remember(state.query, use24Hour) { QuickAddParser.parse(state.query, use24Hour = use24Hour) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What's the event?") },
            placeholder = { Text("e.g. \"Dentist friday 9:30am\"") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canSave) onSave(use24Hour) }),
        )

        PreviewRow(parsed)

        if (state.calendars.size > 1) {
            CalendarPicker(
                calendars = state.calendars,
                selectedId = state.selectedCalendarId,
                onSelect = onSelectCalendar,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onSave(use24Hour) }, enabled = state.canSave) {
                Text("Add event", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PreviewRow(parsed: app.foscal.core.model.QuickAddResult) {
    val zone = ZoneId.systemDefault()
    val date = parsed.date ?: LocalDate.now()
    val dateText = date.format(rememberDateFormatter("EEE, MMM d"))
    val timeText = if (parsed.allDay) {
        "All day"
    } else {
        val t = parsed.time ?: defaultNextHour()
        t.format(rememberTimeFormatter())
    }
    val title = parsed.title.ifBlank { "(Untitled)" }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "$dateText  •  $timeText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (parsed.allDay) {
            Text(
                "All-day event",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultNextHour(): LocalTime =
    java.time.ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0).toLocalTime()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPicker(
    calendars: List<app.foscal.core.model.Calendar>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.displayName ?: "Calendar",
            onValueChange = {},
            readOnly = true,
            label = { Text("Calendar") },
            leadingIcon = {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(selected?.color ?: 0xFF1976D2.toInt())),
                )
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
            calendars.forEach { cal ->
                DropdownMenuItem(
                    text = { Text(cal.displayName) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(cal.color)),
                        )
                    },
                    onClick = {
                        onSelect(cal.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
