package app.calendarium.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.calendarium.R
import app.calendarium.core.data.CalendarPermissionState
import app.calendarium.core.model.AccentColor
import app.calendarium.core.model.ThemeMode
import app.calendarium.core.ui.theme.BricolageFamily
import app.calendarium.ui.settings.AccentPicker
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat

private enum class OnboardingStep { WELCOME, CALENDARS, NOTIFICATIONS }

@Composable
fun OnboardingRoute(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionResult()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) step = OnboardingStep.CALENDARS
    }

    var notificationsEnabled by remember { mutableStateOf(hasNotificationPermission(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsEnabled = granted
    }

    LaunchedEffect(state.calendarPermissionGranted, step) {
        if (step == OnboardingStep.WELCOME && state.calendarPermissionGranted) {
            step = OnboardingStep.CALENDARS
        }
    }

    LaunchedEffect(state.setupComplete) {
        if (state.setupComplete) step = OnboardingStep.NOTIFICATIONS
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onContinue()
    }

    Scaffold(
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StepProgress(
                active = when (step) {
                    OnboardingStep.WELCOME -> 0
                    OnboardingStep.CALENDARS -> 1
                    OnboardingStep.NOTIFICATIONS -> 2
                },
            )
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onStart = {
                        if (state.calendarPermissionGranted) {
                            step = OnboardingStep.CALENDARS
                        } else {
                            calendarPermissionLauncher.launch(
                                CalendarPermissionState.REQUIRED_PERMISSIONS,
                            )
                        }
                    },
                )
                OnboardingStep.CALENDARS -> CalendarSetupStep(
                    state = state,
                    onUseLocal = viewModel::useLocalOnly,
                    onUseExisting = viewModel::useExisting,
                    onUseDavx = {
                        viewModel.openDavxOrStore()
                        viewModel.finishAfterSync()
                    },
                )
                OnboardingStep.NOTIFICATIONS -> PersonalizeStep(
                    completing = state.completing,
                    themeMode = state.themeMode,
                    onThemeSelect = viewModel::setThemeMode,
                    accentColor = state.accentColor,
                    accentCustomColor = state.accentCustomColor,
                    onAccentSelect = viewModel::setAccentColor,
                    onCustomAccentPick = viewModel::setCustomAccentColor,
                    notificationsEnabled = notificationsEnabled,
                    onNotificationsToggle = { want ->
                        when {
                            !want -> notificationsEnabled = false
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !hasNotificationPermission(context) ->
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> notificationsEnabled = true
                        }
                    },
                    mapsEnabled = state.mapsEnabled,
                    onMapsToggle = viewModel::setMapsEnabled,
                    onDone = viewModel::completeOnboarding,
                )
            }

            if (state.completing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Setting up…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StepProgress(active: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (index <= active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        CalendariumMark(Modifier.size(104.dp))
        Spacer(Modifier.height(30.dp))
        CalendariumWordmark()
        Text(
            "Your calendars, quietly organized. Local first, open source, and ready for DAVx5 sync.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.height(140.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Get started", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "No account needed · Open source",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun CalendarSetupStep(
    state: OnboardingUiState,
    onUseLocal: () -> Unit,
    onUseExisting: () -> Unit,
    onUseDavx: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Your calendars",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "Choose where Calendarium should start. You can change visible calendars later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceCard(
            icon = Icons.Outlined.DevicesOther,
            title = "Use offline",
            subtitle = "Create a local calendar that stays on this device.",
            buttonText = "Create local calendar",
            enabled = !state.completing,
            onClick = onUseLocal,
        )
        ChoiceCard(
            icon = Icons.Outlined.CalendarMonth,
            title = "Use existing calendars",
            subtitle = "Show calendars Android already has on this device.",
            buttonText = "Continue",
            enabled = !state.completing,
            onClick = onUseExisting,
        )
        ChoiceCard(
            icon = Icons.Outlined.CloudSync,
            title = "Sync with CalDAV",
            subtitle = if (state.davxStatus == DAVxStatus.INSTALLED) {
                "DAVx5 is installed. Open it to add Nextcloud, ownCloud, or another CalDAV account."
            } else {
                "Install DAVx5 from F-Droid to add Nextcloud, ownCloud, or another CalDAV account."
            },
            buttonText = if (state.davxStatus == DAVxStatus.INSTALLED) "Open DAVx5" else "Install DAVx5",
            enabled = !state.completing,
            onClick = onUseDavx,
        )
    }
}

@Composable
private fun PersonalizeStep(
    completing: Boolean,
    themeMode: ThemeMode,
    onThemeSelect: (ThemeMode) -> Unit,
    accentColor: AccentColor,
    accentCustomColor: Int,
    onAccentSelect: (AccentColor) -> Unit,
    onCustomAccentPick: (Int) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsToggle: (Boolean) -> Unit,
    mapsEnabled: Boolean,
    onMapsToggle: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Make it yours",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "Personalize Calendarium. You can change any of this later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThemeCard(selected = themeMode, onSelect = onThemeSelect)
        AccentCard(
            selected = accentColor,
            customColor = accentCustomColor,
            onSelectPreset = onAccentSelect,
            onPickCustom = onCustomAccentPick,
        )
        ToggleCard(
            icon = Icons.Outlined.Notifications,
            title = "Reminders",
            subtitle = "Get a heads-up before events, using only your local calendar data.",
            checked = notificationsEnabled,
            onToggle = onNotificationsToggle,
        )
        ToggleCard(
            icon = Icons.Outlined.Map,
            title = "Pick locations on a map",
            subtitle = "Optional — the only feature that uses the internet. Choose a location on " +
                "an OpenStreetMap map instead of typing it. The map and address lookup contact " +
                "OpenStreetMap's servers (no ads, no tracking profile), so they can see your IP " +
                "and the places you look up. Everything else stays offline.",
            checked = mapsEnabled,
            onToggle = onMapsToggle,
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDone,
            enabled = !completing,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Start using Calendarium", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AccentCard(
    selected: AccentColor,
    customColor: Int,
    onSelectPreset: (AccentColor) -> Unit,
    onPickCustom: (Int) -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "Main color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AccentPicker(
                selected = selected,
                customColor = customColor,
                onSelectPreset = onSelectPreset,
                onPickCustom = onPickCustom,
            )
        }
    }
}

@Composable
private fun ThemeCard(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark",
    )
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
}

@Composable
private fun ToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = checked, onCheckedChange = onToggle)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Whether reminder notifications may be shown — always true before Android 13's runtime grant. */
private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
private fun CalendariumWordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Calendarium",
            style = MaterialTheme.typography.displayMedium.copy(fontFamily = BricolageFamily),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The Calendarium glyph: a minimal calendar showing "31" in Bricolage Grotesque on a
 * brand-blue squircle. Reuses the launcher foreground drawable over the same blue field
 * so the in-app mark and the home-screen icon stay pixel-identical.
 */
@Composable
private fun CalendariumMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(percent = 26))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF3B86EE), Color(0xFF1A73E8))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(buttonText) }
        }
    }
}
