package app.foscal.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.R
import app.foscal.core.data.CalendarPermissionState
import app.foscal.notifications.HealthSeverity
import app.foscal.notifications.ReminderFix
import app.foscal.notifications.ReminderFixIntents
import app.foscal.notifications.ReminderHealth
import app.foscal.notifications.ReminderIssue
import app.foscal.ui.util.UiText
import app.foscal.ui.util.asString
import app.foscal.ui.util.uiPlural
import app.foscal.ui.util.uiText
import java.time.Duration
import java.time.Instant

/**
 * "Not getting reminders?" — the panel that answers the one support question a calendar app cannot
 * answer any other way.
 *
 * Reminder delivery depends on a stack of things that are invisible from inside the app: a runtime
 * permission, a notification channel, Doze, the standby bucket, and on many devices a vendor
 * app-killer with no API at all. When one of them is off the user simply stops being reminded, with
 * nothing on screen to suggest why. This lists what is actually wrong, in the order it matters, and
 * puts the system screen that fixes it one tap away.
 *
 * Collapsed by default: on a healthy device it has nothing to say, and a settings screen that opens
 * with a troubleshooting list implies the feature is unreliable.
 */
@Composable
fun ReminderDiagnosticsCard(
    modifier: Modifier = Modifier,
    viewModel: ReminderDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Every fix button leaves the app for a system screen. Re-probing on the way back is what makes
    // the panel reflect what the user just did rather than what was true when they opened it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            DiagnosticsHeader(
                issues = state.issues,
                loading = state.loading,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.issues.forEach { issue ->
                        HorizontalDivider()
                        IssueRow(
                            issue = issue,
                            onFix = { fix ->
                                runFix(
                                    context = context,
                                    fix = fix,
                                    onGrantCalendar = {
                                        permissionLauncher.launch(
                                            CalendarPermissionState.REQUIRED_PERMISSIONS,
                                        )
                                    },
                                    onResync = viewModel::resync,
                                )
                            },
                        )
                    }
                    HorizontalDivider()
                    SyncSummary(
                        health = state.health,
                        resyncing = state.resyncing,
                        onResync = viewModel::resync,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsHeader(
    issues: List<ReminderIssue>,
    loading: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val blocking = issues.count { it.severity == HealthSeverity.BLOCKING }
    val icon = when {
        blocking > 0 -> Icons.Outlined.ErrorOutline
        issues.isNotEmpty() -> Icons.Outlined.WarningAmber
        else -> Icons.Outlined.CheckCircle
    }
    val tint = when {
        blocking > 0 -> MaterialTheme.colorScheme.error
        issues.isNotEmpty() -> WarningTint
        else -> MaterialTheme.colorScheme.primary
    }
    val subtitle = when {
        loading -> stringResource(R.string.settings_diag_checking)
        blocking > 0 -> pluralStringResource(R.plurals.settings_diag_blocking, blocking, blocking)
        issues.isNotEmpty() ->
            pluralStringResource(R.plurals.settings_diag_warnings, issues.size, issues.size)
        else -> stringResource(R.string.settings_diag_all_good)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.settings_diag_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_collapse else R.string.settings_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IssueRow(issue: ReminderIssue, onFix: (ReminderFix) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (issue.severity == HealthSeverity.BLOCKING) {
                    Icons.Outlined.ErrorOutline
                } else {
                    Icons.Outlined.WarningAmber
                },
                contentDescription = null,
                tint = if (issue.severity == HealthSeverity.BLOCKING) {
                    MaterialTheme.colorScheme.error
                } else {
                    WarningTint
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                issue.title.asString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            issue.detail.asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fix = issue.fix
        val fixLabel = issue.fixLabel
        if (fix != null && fixLabel != null) {
            TextButton(
                onClick = { onFix(fix) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(fixLabel.asString()) }
        }
    }
}

@Composable
private fun SyncSummary(health: ReminderHealth?, resyncing: Boolean, onResync: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                lastCheckedLabel(health?.lastSyncAt).asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val armed = health?.alarmsArmed ?: 0
            Text(
                pluralStringResource(R.plurals.settings_diag_scheduled, armed, armed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (resyncing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onResync) { Text(stringResource(R.string.settings_diag_resync)) }
        }
    }
}

/**
 * Coarse and relative on purpose. The exact minute is noise — what the user needs to know is
 * whether this happened recently or has quietly not happened for two days.
 */
private fun lastCheckedLabel(at: Instant?): UiText {
    if (at == null) return uiText(R.string.settings_diag_checked_never)
    val elapsed = Duration.between(at, Instant.now())
    return when {
        // The clock moved backwards; not worth a second message.
        elapsed.isNegative -> uiText(R.string.settings_diag_checked_now)
        elapsed.toMinutes() < 1 -> uiText(R.string.settings_diag_checked_now)
        elapsed.toHours() < 1 ->
            uiPlural(R.plurals.settings_diag_checked_minutes, elapsed.toMinutes().toInt())
        elapsed.toDays() < 1 ->
            uiPlural(R.plurals.settings_diag_checked_hours, elapsed.toHours().toInt())
        else -> uiPlural(R.plurals.settings_diag_checked_days, elapsed.toDays().toInt())
    }
}

/**
 * Amber for "this still works, but it may be late".
 *
 * Hard-coded rather than taken from the scheme: Material 3 has no warning role, and both `error`
 * and `tertiary` would misreport the severity — one as fatal, the other as decorative.
 */
private val WarningTint = Color(0xFFB26A00)

private fun runFix(
    context: Context,
    fix: ReminderFix,
    onGrantCalendar: () -> Unit,
    onResync: () -> Unit,
) {
    when (fix) {
        ReminderFix.GRANT_CALENDAR -> onGrantCalendar()
        ReminderFix.RESYNC -> onResync()
        else -> {
            val intent = ReminderFixIntents.intentFor(context, fix)
            if (intent == null) {
                toast(context, context.getString(R.string.settings_diag_no_setting))
                return
            }
            // Some of these screens exist on the device but refuse to be launched from outside the
            // system app, and a settings shortcut is never worth taking the app down for.
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                toast(context, context.getString(R.string.settings_diag_open_failed))
            } catch (_: SecurityException) {
                toast(context, context.getString(R.string.settings_diag_open_failed))
            }
        }
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
