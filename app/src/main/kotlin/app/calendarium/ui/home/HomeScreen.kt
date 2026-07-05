package app.calendarium.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.calendarium.ui.agenda.AgendaRoute
import app.calendarium.ui.month.MonthRoute
import app.calendarium.ui.settings.SettingsSheet

private enum class HomeTab(val label: String, val icon: ImageVector) {
    Month("Month", Icons.Outlined.CalendarMonth),
    Agenda("Agenda", Icons.Outlined.ViewAgenda),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onOpenEvent: (Long) -> Unit,
    onOpenEditor: (calendarId: Long?, startMillis: Long?, endMillis: Long?) -> Unit,
) {
    var tab by remember { mutableStateOf(HomeTab.Month) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenEditor(null, null, null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New event") },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                HomeTab.Month -> MonthRoute(
                    onEventClick = onOpenEvent,
                    onNewEvent = { start, end -> onOpenEditor(null, start, end) },
                    onOpenSettings = { showSettings = true },
                )
                HomeTab.Agenda -> AgendaRoute(
                    onEventClick = onOpenEvent,
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(onDismiss = { showSettings = false })
    }
}
