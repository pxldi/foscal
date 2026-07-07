package app.calendarium.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "calendarium_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : Preferences {

    override val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    override val hiddenCalendarIds: Flow<Set<String>> =
        context.dataStore.data.map { it[HIDDEN_CALENDARS] ?: emptySet() }

    override val defaultReminderMinutes: Flow<Int?> =
        context.dataStore.data.map { it[DEFAULT_REMINDER]?.takeIf { m -> m != -1 } }

    override suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    override suspend fun setHiddenCalendars(ids: Set<String>) {
        context.dataStore.edit { prefs -> prefs[HIDDEN_CALENDARS] = ids }
    }

    override suspend fun setDefaultReminder(minutes: Int?) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_REMINDER] = minutes ?: -1 }
    }

    companion object {
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val HIDDEN_CALENDARS = stringSetPreferencesKey("hidden_calendars")
        private val DEFAULT_REMINDER = intPreferencesKey("default_reminder_minutes")
    }
}
