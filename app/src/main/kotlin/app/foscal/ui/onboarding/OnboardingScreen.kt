package app.foscal.ui.onboarding

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Contrast
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.foscal.R
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
import app.foscal.core.ui.theme.BricolageFamily
import app.foscal.core.ui.theme.Motion
import app.foscal.ui.permission.CalendarAccessOff
import app.foscal.ui.permission.isDeniedForGood
import app.foscal.ui.permission.openAppSettings
import app.foscal.ui.permission.openNotificationSettings
import app.foscal.ui.settings.AccentPicker
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat

private enum class OnboardingStep { WELCOME, PREPARING, CALENDARS, SETTLING, NOTIFICATIONS }

/**
 * How long the setup screen holds before showing what it found.
 *
 * Granting the permission used to swap the screen out on the same frame the system dialog
 * disappeared, which reads as a glitch rather than as progress — the dialog vanishes and a
 * different screen is simply there. The app does have something to do at that moment (it can
 * finally read the calendars on the phone, and check whether DAVx5 is installed), so this is a
 * floor on how long that is shown, not an invented wait: enough for the answer to look like it was
 * looked up, and short enough that nobody is kept waiting for it.
 */
private const val PreparingMillis = 800L

/** The same beat after the calendar choice, before the last step. */
private const val SettlingMillis = 1100L

@Composable
fun OnboardingRoute(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }

    // After two denials "Get started" relaunched a request Android no longer shows, so the button
    // did nothing at all. This switches the welcome step to a way out through settings.
    var calendarDeniedForGood by rememberSaveable { mutableStateOf(false) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionResult()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            step = OnboardingStep.PREPARING
        } else {
            calendarDeniedForGood = isDeniedForGood(context, Manifest.permission.READ_CALENDAR)
        }
    }

    var notificationsEnabled by remember { mutableStateOf(hasNotificationPermission(context)) }
    // Set while the user is in notification settings, so the switch catches up on the way back.
    var awaitingNotificationSettings by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsEnabled = granted
        if (!granted && isDeniedForGood(context, Manifest.permission.POST_NOTIFICATIONS)) {
            awaitingNotificationSettings = openNotificationSettings(context)
        }
    }

    LaunchedEffect(state.calendarPermissionGranted, step) {
        if (step == OnboardingStep.WELCOME && state.calendarPermissionGranted) {
            step = OnboardingStep.PREPARING
        }
    }

    LaunchedEffect(step) {
        when (step) {
            OnboardingStep.PREPARING -> {
                delay(PreparingMillis)
                step = OnboardingStep.CALENDARS
            }
            // Choosing a calendar can finish instantly — picking one that already exists writes
            // nothing at all — and a screen that vanishes the moment it is touched reads as a
            // misfire rather than as having worked. The beat is what makes the choice land.
            OnboardingStep.SETTLING -> {
                delay(SettlingMillis)
                step = OnboardingStep.NOTIFICATIONS
            }
            else -> Unit
        }
    }

    LaunchedEffect(state.setupComplete) {
        if (state.setupComplete) step = OnboardingStep.SETTLING
    }

    LaunchedEffect(state.finished) {
        if (state.finished) onContinue()
    }

    // The battery exemption is granted on a system screen, so its result only becomes visible on
    // the way back into the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshBatteryStatus()
        if (awaitingNotificationSettings) {
            awaitingNotificationSettings = false
            notificationsEnabled = hasNotificationPermission(context)
        }
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
                    // Already on the second segment: the wait is part of getting there, and a
                    // progress bar that moves only once the waiting is over is not progress.
                    OnboardingStep.PREPARING -> 1
                    OnboardingStep.CALENDARS -> 1
                    OnboardingStep.SETTLING -> 2
                    OnboardingStep.NOTIFICATIONS -> 2
                },
            )
            AnimatedContent(
                targetState = step,
                // Each step arrives from the right and the one before it leaves to the left,
                // so the sequence reads as forward motion rather than as screens being swapped.
                transitionSpec = {
                    (
                        fadeIn(tween(Motion.DurationMedium)) +
                            slideInHorizontally { width -> width / 6 }
                        ) togetherWith (
                        fadeOut(tween(Motion.DurationShort)) +
                            slideOutHorizontally { width -> -width / 6 }
                        ) using SizeTransform(clip = false)
                },
                label = "onboardingStep",
            ) { current ->
                when (current) {
                    OnboardingStep.WELCOME -> WelcomeStep(
                        accessOff = calendarDeniedForGood,
                        onOpenSettings = { openAppSettings(context) },
                        onStart = {
                            if (state.calendarPermissionGranted) {
                                step = OnboardingStep.PREPARING
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
                        batteryOptimized = state.batteryOptimized,
                        onOpenBatterySettings = {
                            if (!viewModel.openBatterySettings()) {
                                Toast.makeText(
                                    context,
                                    "This phone has no battery settings screen",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        mapsEnabled = state.mapsEnabled,
                        onMapsToggle = viewModel::setMapsEnabled,
                        onDone = viewModel::completeOnboarding,
                    )
                    OnboardingStep.PREPARING -> PreparingStep("Checking what's on this phone\u2026")
                    OnboardingStep.SETTLING -> PreparingStep("Setting your calendar up\u2026")
                }
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

/** The waiting beat, held for [PreparingMillis] or [SettlingMillis] depending on which one. */
@Composable
private fun PreparingStep(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
private fun WelcomeStep(accessOff: Boolean, onOpenSettings: () -> Unit, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        FoscalMark(Modifier.size(104.dp))
        Spacer(Modifier.height(30.dp))
        FoscalWordmark()
        Spacer(Modifier.height(14.dp))
        Text(
            "A Material Design 3 open source calendar",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        // Three facts, not a pitch. Each one is something the app either does or does not do, and
        // a first screen is the only place a calendar gets to say "nothing leaves this phone"
        // before the user has to take that on trust.
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WelcomePoint("No account, no network, no tracking.")
            WelcomePoint("Syncs with Nextcloud, ownCloud and any CalDAV server through DAVx⁵.")
            WelcomePoint("Day, week, month and agenda views.")
        }
        Spacer(Modifier.height(52.dp))
        if (accessOff) {
            CalendarAccessOff(onOpenSettings = onOpenSettings, modifier = Modifier.fillMaxWidth())
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Get started", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One line of the welcome list, with a dot rather than a bullet glyph the font may not have. */
@Composable
private fun WelcomePoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
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
            "Pick a starting point. You can change this later.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceCard(
            icon = Icons.Outlined.DevicesOther,
            title = "Start fresh",
            subtitle = "A new calendar on this phone.",
            buttonText = "Create calendar",
            enabled = !state.completing,
            onClick = onUseLocal,
        )
        ChoiceCard(
            icon = Icons.Outlined.CalendarMonth,
            title = "Use what's already here",
            subtitle = "The ones your phone already has.",
            buttonText = "Continue",
            enabled = !state.completing,
            onClick = onUseExisting,
        )
        ChoiceCard(
            icon = Icons.Outlined.CloudSync,
            title = "Sync with CalDAV",
            subtitle = if (state.davxStatus == DAVxStatus.INSTALLED) {
                "Nextcloud, ownCloud and the rest, through DAVx⁵."
            } else {
                "Needs DAVx⁵, free on F-Droid."
            },
            buttonText = if (state.davxStatus == DAVxStatus.INSTALLED) "Open DAVx⁵" else "Install DAVx⁵",
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
    batteryOptimized: Boolean,
    onOpenBatterySettings: () -> Unit,
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
            "You can change these settings later.",
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
            subtitle = "A heads-up before your events.",
            checked = notificationsEnabled,
            onToggle = onNotificationsToggle,
        )
        // Only while it is still a problem. Once the exemption is granted the card has nothing to
        // offer, and leaving it on screen reads as a step that failed.
        if (notificationsEnabled && batteryOptimized) {
            ActionCard(
                icon = Icons.Outlined.BatteryAlert,
                title = "Reminders may arrive late",
                subtitle = "Battery saver holds them back until the phone wakes. Set Foscal to " +
                    "unrestricted to fix it.",
                buttonText = "Open battery settings",
                onClick = onOpenBatterySettings,
            )
        }
        ToggleCard(
            icon = Icons.Outlined.Map,
            title = "Pick locations on a map",
            subtitle = "Tap a place instead of typing it. Your search goes to OpenStreetMap.",
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
            Text("Start using Foscal", fontWeight = FontWeight.SemiBold)
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
                    "Accent colour",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AccentPicker(
                selected = selected,
                customColor = customColor,
                onSelectPreset = onSelectPreset,
                onPickCustom = onPickCustom,
                label = null,
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
                    Icons.Outlined.Contrast,
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
    val haptics = LocalHapticFeedback.current
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
                Switch(
                    checked = checked,
                    onCheckedChange = {
                        haptics.performHapticFeedback(
                            if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                        onToggle(it)
                    },
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A card whose subject is not a preference Foscal can hold but a system screen the user must visit.
 * Same shape as [ToggleCard] with a button where the switch would be — a switch here would imply
 * Foscal can turn the setting on itself, which is exactly what it cannot do.
 */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
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
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
            ) { Text(buttonText) }
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
private fun FoscalWordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Foscal",
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
 * The Foscal glyph: the month-grid mark on the deep-ink squircle. Renders the
 * self-contained badge drawable so the in-app mark and the home-screen launcher icon
 * stay pixel-identical.
 */
@Composable
private fun FoscalMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_foscal_badge),
        contentDescription = null,
        modifier = modifier,
    )
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
