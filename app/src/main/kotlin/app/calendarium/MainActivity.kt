package app.calendarium

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.data.CalendarPermissionState
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.ui.theme.CalendariumTheme
import app.calendarium.ui.nav.CalendariumNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefs: UserPreferencesRepository

    @Inject
    lateinit var permissionState: CalendarPermissionState

    private var pendingEventId by mutableLongStateOf(-1L)
    private var pendingQuickAdd by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — the app still functions */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        pendingEventId = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        pendingQuickAdd = intent.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false)
        setContent {
            val onboardingDone by prefs.onboardingCompleted
                .collectAsStateWithLifecycle(initialValue = null)
            val openEventId = pendingEventId
            val openQuickAdd = pendingQuickAdd
            CalendariumTheme {
                when (val done = onboardingDone) {
                    null -> { /* splash while DataStore loads */ }
                    else -> CalendariumNavHost(
                        startOnboarding = done.not(),
                        openEventId = openEventId,
                        openQuickAdd = openQuickAdd,
                        onEventConsumed = { pendingEventId = -1L },
                        onQuickAddConsumed = { pendingQuickAdd = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        if (id > 0L) pendingEventId = id
        if (intent.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false)) pendingQuickAdd = true
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted access from the system settings screen; pick it up so
        // provider-backed flows and reminders resume without needing a restart.
        permissionState.refresh()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
        const val EXTRA_OPEN_QUICK_ADD = "open_quick_add"
    }
}
