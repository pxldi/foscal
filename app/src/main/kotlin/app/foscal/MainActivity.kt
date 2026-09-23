package app.foscal

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.data.Preferences
import app.foscal.core.data.UserPreferencesRepository
import app.foscal.core.model.AccentColor
import app.foscal.core.model.EventColorStrength
import app.foscal.core.model.ThemeMode
import app.foscal.core.ui.theme.FoscalTheme
import app.foscal.ui.nav.FoscalNavHost
import app.foscal.ui.util.LocalEventColorStrength
import app.foscal.ui.util.LocalEventTextScale
import app.foscal.ui.util.LocalUse24HourClock
import app.foscal.ui.util.LocalWrapEventTitles
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
        // A recreated activity still holds its launch intent, and the request in it was carried
        // out the first time: routing it again reopened the event or the import on every rotation.
        if (savedInstanceState == null) pendingRoute = routeFor(intent)
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
            val colorStrength by prefs.eventColorStrength
                .collectAsStateWithLifecycle(initialValue = EventColorStrength.Default)
            val eventTextScale by prefs.eventTextScalePercent
                .collectAsStateWithLifecycle(initialValue = Preferences.DEFAULT_EVENT_TEXT_SCALE)
            val wrapTitles by prefs.wrapEventTitles
                .collectAsStateWithLifecycle(initialValue = true)
            val route = pendingRoute
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // enableEdgeToEdge() on its own picks icon colours from the system's dark mode, so a
            // forced Light or Dark theme put light icons on a light bar or dark on dark.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        LightNavigationScrim,
                        DarkNavigationScrim,
                    ) { darkTheme },
                )
                onDispose {}
            }
            FoscalTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
                accent = accent,
                customSeed = androidx.compose.ui.graphics.Color(customAccent),
            ) {
                CompositionLocalProvider(
                    LocalUse24HourClock provides use24Hour,
                    LocalEventColorStrength provides colorStrength,
                    LocalEventTextScale provides eventTextScale / 100f,
                    LocalWrapEventTitles provides wrapTitles,
                ) {
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

        // The scrims enableEdgeToEdge() uses by default, which androidx does not make public.
        private val LightNavigationScrim = AndroidColor.argb(0xe6, 0xff, 0xff, 0xff)
        private val DarkNavigationScrim = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
