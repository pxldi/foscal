package app.foscal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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

    private var pendingRoute by mutableStateOf<IntentRoute>(IntentRoute.None)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute = routeFor(intent)
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
            val route = pendingRoute
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
                            route = route,
                            onRouteConsumed = { pendingRoute = IntentRoute.None },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Only when the new intent actually asks for something. The activity is singleTop, so a
        // plain relaunch from the launcher arrives here too, and clearing the pending route on
        // that would cancel a request the user has not seen carried out yet.
        val next = routeFor(intent)
        if (next != IntentRoute.None) pendingRoute = next
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
