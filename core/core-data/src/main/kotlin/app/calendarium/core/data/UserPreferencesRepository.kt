package app.calendarium.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "calendarium_prefs")

/**
 * App-local preferences. Stored separately from the system Calendar Provider so that
 * choices like "which calendars are visible in Calendarium" don't mutate state shared
 * with every other calendar app on the device.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    /** Calendar IDs the user has hidden inside Calendarium (subset of system-visible). */
    val hiddenCalendarIds: Flow<Set<String>> =
        context.dataStore.data.map { it[HIDDEN_CALENDARS] ?: emptySet() }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    suspend fun setHiddenCalendars(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[HIDDEN_CALENDARS] = ids
        }
    }

    companion object {
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val HIDDEN_CALENDARS = stringSetPreferencesKey("hidden_calendars")
    }
}
