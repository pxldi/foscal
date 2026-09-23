package app.foscal.ui.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

/**
 * Whether a request for [permission] that just came back denied will never show its dialog again.
 *
 * After the second "Don't allow" Android stops asking and answers every later request with an
 * instant denial, so a button that only relaunches the request does nothing and says nothing. The
 * rationale flag is false both then and before the first request, which is why this must only be
 * read in the result callback of a request that was denied.
 */
fun isDeniedForGood(context: Context, permission: String): Boolean {
    val activity = context.findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

/** Opens this app's page in system settings, where its permissions can be switched on. */
fun openAppSettings(context: Context): Boolean = startSafely(
    context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
)

/** Opens this app's notification settings, falling back to its settings page. */
fun openNotificationSettings(context: Context): Boolean =
    startSafely(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    ) || openAppSettings(context)

private fun startSafely(context: Context, intent: Intent): Boolean = try {
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * What the permission gate and onboarding show once calendar access can only be given in
 * settings. It says why the app needs it, because the settings page will not.
 */
@Composable
fun CalendarAccessOff(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Calendar access is off",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Foscal keeps your events in Android's calendar storage, so it cannot show or save " +
                "anything without access. Android will not ask again. In settings, open " +
                "Permissions and allow Calendar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open app settings")
        }
    }
}
