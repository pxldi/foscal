package app.foscal.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foscal.ui.calendars.CalendarRow
import app.foscal.ui.contrastColor

/**
 * Everything that adjusts what you are looking at, in one panel within thumb reach.
 *
 * Opened from the bottom bar rather than an edge swipe: on a phone with gesture navigation the
 * system owns every screen edge — left and right are back, the bottom is home — so there is no
 * edge drag left for an app to claim. A panel that rises from the button that opened it needs no
 * edge, and puts its contents where the hand already is instead of at the top-left corner.
 *
 * The views collapse to a single row so the calendars are the body of the sheet rather than an
 * afterthought below five stacked rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSheet(
    current: CalendarView,
    calendars: List<CalendarRow>,
    onSelect: (CalendarView) -> Unit,
    onToggleCalendar: (CalendarRow) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            ViewRow(current = current, onSelect = onSelect)
            SheetDivider()
            SectionLabel("Calendars")
            calendars.forEach { row ->
                CalendarToggle(row = row, onToggle = { onToggleCalendar(row) })
            }
            SheetDivider()
            SheetAction(
                label = "Settings",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ViewRow(current: CalendarView, onSelect: (CalendarView) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CalendarView.entries.forEach { view ->
            val selected = view == current
            val tint = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable(role = Role.Tab) { onSelect(view) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ViewGlyph(view = view, tint = tint, modifier = Modifier.size(22.dp))
                Text(
                    view.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A checkbox in the calendar's own colour, which is why it replaces the switch this used to be in
 * Settings: it toggles the calendar and tells you what colour that calendar draws in, from one
 * control instead of two.
 */
@Composable
private fun CalendarToggle(row: CalendarRow, onToggle: () -> Unit) {
    val color = Color(row.calendar.color)
    val visible = !row.isHidden
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (visible) color else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (visible) color else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (visible) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = contrastColor(row.calendar.color),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            row.calendar.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (visible) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Outlined.Settings,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
