package app.foscal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.data.UserPreferencesRepository
import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
import app.foscal.core.ui.theme.FoscalTheme
import app.foscal.ui.nav.FoscalNavHost
import app.foscal.ui.util.LocalUse24HourClock
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefs: UserPreferencesRepository

    @Inject
    lateinit var permissionState: CalendarPermissionState

    private var pendingEventId by mutableLongStateOf(-1L)
    private var pendingInstanceStart by mutableLongStateOf(0L)
    private var pendingQuickAdd by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingEventId = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        pendingInstanceStart = intent.getLongExtra(EXTRA_OPEN_INSTANCE_START, 0L)
        pendingQuickAdd = intent.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false)
        setContent {
            val onboardingDone by prefs.onboardingCompleted
                .collectAsStateWithLifecycle(initialValue = null)
            val accent by prefs.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.Default)
            val customAccent by prefs.accentCustomColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.DEFAULT_CUSTOM_COLOR)
            val dynamicColor by prefs.dynamicColor
                .collectAsStateWithLifecycle(initialValue = false)
            val themeMode by prefs.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.Default)
            val use24Hour by prefs.use24HourClock
                .collectAsStateWithLifecycle(initialValue = true)
            val openEventId = pendingEventId
            val openInstanceStart = pendingInstanceStart
            val openQuickAdd = pendingQuickAdd
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FoscalTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
                accent = accent,
                customSeed = androidx.compose.ui.graphics.Color(customAccent),
            ) {
                CompositionLocalProvider(LocalUse24HourClock provides use24Hour) {
                    when (val done = onboardingDone) {
                        null -> { /* splash while DataStore loads */ }
                        else -> FoscalNavHost(
                            startOnboarding = done.not(),
                            openEventId = openEventId,
                            openInstanceStartMillis = openInstanceStart,
                            openQuickAdd = openQuickAdd,
                            onEventConsumed = { pendingEventId = -1L },
                            onQuickAddConsumed = { pendingQuickAdd = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_OPEN_EVENT_ID, -1L)
        if (id > 0L) {
            pendingEventId = id
            pendingInstanceStart = intent.getLongExtra(EXTRA_OPEN_INSTANCE_START, 0L)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_QUICK_ADD, false)) pendingQuickAdd = true
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted access from the system settings screen; pick it up so
        // provider-backed flows and reminders resume without needing a restart.
        permissionState.refresh()
    }

    companion object {
        const val EXTRA_OPEN_EVENT_ID = "open_event_id"
        const val EXTRA_OPEN_INSTANCE_START = "open_instance_start"
        const val EXTRA_OPEN_QUICK_ADD = "open_quick_add"
    }
}
