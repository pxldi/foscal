package app.foscal.ui.quickadd

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.R
import app.foscal.core.model.QuickAddParser
import app.foscal.ui.feedback.FeedbackSnackbarHost
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.currentLocale
import app.foscal.ui.util.rememberSkeletonFormatter
import app.foscal.ui.util.rememberTimeFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Formatter
import java.util.Locale

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
                title = { Text(stringResource(R.string.quick_add_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_cancel),
                        )
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
                    onSave = { use24Hour, locale -> viewModel.save(use24Hour, locale) },
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
    onSave: (use24Hour: Boolean, locale: Locale) -> Unit,
) {
    val use24Hour = LocalUse24HourClock.current
    val locale = currentLocale()
    val parsed = remember(state.query, use24Hour, locale) {
        QuickAddParser.parse(state.query, use24Hour = use24Hour, locale = locale)
    }
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
            label = { Text(stringResource(R.string.quick_add_label)) },
            placeholder = { Text(stringResource(R.string.quick_add_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.canSave) onSave(use24Hour, locale) }),
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
            TextButton(onClick = { onSave(use24Hour, locale) }, enabled = state.canSave) {
                Text(stringResource(R.string.quick_add_save), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PreviewRow(parsed: app.foscal.core.model.QuickAddResult) {
    val zone = ZoneId.systemDefault()
    val date = parsed.date ?: LocalDate.now()
    val dayFormatter = rememberSkeletonFormatter("EEEMMMd")
    val context = LocalContext.current
    val locale = currentLocale()
    val dateText = parsed.endDate?.let { dateRangeText(context, date, it, locale) } ?: date.format(dayFormatter)
    val timeFormatter = rememberTimeFormatter()
    val timeText = if (parsed.allDay) {
        stringResource(R.string.view_all_day)
    } else {
        val t = parsed.time ?: defaultNextHour()
        val end = parsed.endTime
        if (end == null) {
            t.format(timeFormatter)
        } else {
            stringResource(R.string.quick_add_time_range, t.format(timeFormatter), end.format(timeFormatter))
        }
    }
    val untitled = stringResource(R.string.quick_add_untitled)
    val title = parsed.title.ifBlank { untitled }
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
                stringResource(R.string.quick_add_all_day_event),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "19.–23. Okt." in German, "Oct 19 – 23" in English: how a range shares its month is the language's. */
private fun dateRangeText(context: Context, start: LocalDate, end: LocalDate, locale: Locale): String {
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    flags = flags or if (start.year == end.year && start.year == LocalDate.now().year) {
        DateUtils.FORMAT_NO_YEAR
    } else {
        DateUtils.FORMAT_SHOW_YEAR
    }
    // Whole days in UTC with an exclusive end, which the platform reads as "up to the day before".
    val startMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val endMillis = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return DateUtils.formatDateRange(context, Formatter(StringBuilder(), locale), startMillis, endMillis, flags, "UTC")
        .toString()
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
            value = selected?.displayName ?: stringResource(R.string.quick_add_calendar),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.quick_add_calendar)) },
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
