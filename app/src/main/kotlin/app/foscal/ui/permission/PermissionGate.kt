package app.foscal.ui.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.data.CalendarPermissionState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gates [content] behind calendar access.
 *
 * The grant/deny answer comes from [CalendarPermissionState], not from a local flag: the
 * repository gates every provider read on that same value, so a gate with its own copy can let
 * [content] render while the repository still believes permission is missing and hands it empty
 * lists. Refreshing the shared state from the permission result — rather than leaning on the
 * activity's `onResume` happening to run first — is what keeps the two in step.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissionState = remember(context) {
        EntryPointAccessors
            .fromApplication(context, PermissionGateEntryPoint::class.java)
            .permissionState()
    }
    val granted by permissionState.granted.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionState.refresh()
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(CalendarPermissionState.REQUIRED_PERMISSIONS)
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Foscal needs calendar access to show your events.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Text(
                "Your data never leaves this app and the system Calendar Provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                launcher.launch(CalendarPermissionState.REQUIRED_PERMISSIONS)
            }) { Text("Grant access") }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PermissionGateEntryPoint {
    fun permissionState(): CalendarPermissionState
}
