package app.calendarium.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.calendarium.core.data.CalendarPermissionState
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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.completeOnboarding()
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
                OnboardingStep.NOTIFICATIONS -> NotificationStep(
                    completing = state.completing,
                    onEnable = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.completeOnboarding()
                        }
                    },
                    onSkip = viewModel::completeOnboarding,
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
        Spacer(Modifier.height(34.dp))
        Text(
            "Calendarium",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            "Your calendars, quietly organized. Local first, open source, and ready for DAVx5 sync.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
private fun NotificationStep(
    completing: Boolean,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            "Make it yours",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "Calendarium can remind you about events. You can keep notifications off and still use every calendar view.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            icon = Icons.Outlined.CheckCircle,
            title = "Reminders",
            subtitle = "Calendarium can nudge you before events, using only your local calendar data.",
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onEnable,
            enabled = !completing,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Allow notifications", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onSkip,
            enabled = !completing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Not now")
        }
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
        }
    }
}

@Composable
private fun CalendariumMark(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = size.minDimension
        drawRoundRect(
            color = primary,
            size = Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.29f),
        )
        val stroke = side * 0.058f
        val white = Color.White
        // Calendar body
        drawRoundRect(
            color = white,
            topLeft = Offset(side * 0.26f, side * 0.30f),
            size = Size(side * 0.48f, side * 0.42f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.11f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Header divider
        drawLine(
            color = white,
            start = Offset(side * 0.26f, side * 0.42f),
            end = Offset(side * 0.74f, side * 0.42f),
            strokeWidth = stroke * 0.9f,
        )
        // Top tabs
        drawLine(
            color = white,
            start = Offset(side * 0.39f, side * 0.255f),
            end = Offset(side * 0.39f, side * 0.345f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = white,
            start = Offset(side * 0.61f, side * 0.255f),
            end = Offset(side * 0.61f, side * 0.345f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // Two dots
        drawCircle(white, radius = side * 0.038f, center = Offset(side * 0.43f, side * 0.56f))
        drawCircle(white, radius = side * 0.038f, center = Offset(side * 0.57f, side * 0.56f))
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
