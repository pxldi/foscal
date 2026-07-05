package app.calendarium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.core.ui.theme.CalendariumTheme
import app.calendarium.ui.nav.CalendariumNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val onboardingDone by prefs.onboardingCompleted
                .collectAsStateWithLifecycle(initialValue = null)
            CalendariumTheme {
                when (val done = onboardingDone) {
                    null -> { /* splash while DataStore loads */ }
                    else -> CalendariumNavHost(startOnboarding = done.not())
                }
            }
        }
    }
}
