package app.foscal.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "foscal_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Preferences {

    override val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    override val hiddenCalendarIds: Flow<Set<String>> =
        context.dataStore.data.map { it[HIDDEN_CALENDARS] ?: emptySet() }

    // An absent key is a fresh install and resolves to the built-in default; only the sentinel
    // means "None", so null reaching a caller is always a deliberate choice to have no reminder.
    override val defaultReminderMinutes: Flow<Int?> =
        context.dataStore.data.map { prefs ->
            (prefs[DEFAULT_REMINDER] ?: Preferences.DEFAULT_REMINDER_MINUTES)
                .takeIf { it != NO_REMINDER }
        }

    override val accentColor: Flow<AccentColor> =
        context.dataStore.data.map { AccentColor.fromKey(it[ACCENT_COLOR]) }

    override val accentCustomColor: Flow<Int> =
        context.dataStore.data.map { it[ACCENT_CUSTOM_COLOR] ?: AccentColor.DEFAULT_CUSTOM_COLOR }

    override val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { ThemeMode.fromKey(it[THEME_MODE]) }

    override val use24HourClock: Flow<Boolean> =
        context.dataStore.data.map { it[USE_24H_CLOCK] ?: true }

    override val osmMapsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[OSM_MAPS_ENABLED] ?: false }

    override suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    override suspend fun setHiddenCalendars(ids: Set<String>) {
        context.dataStore.edit { prefs -> prefs[HIDDEN_CALENDARS] = ids }
    }

    override suspend fun setDefaultReminder(minutes: Int?) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_REMINDER] = minutes ?: NO_REMINDER }
    }

    override suspend fun setAccentColor(accent: AccentColor) {
        context.dataStore.edit { prefs -> prefs[ACCENT_COLOR] = accent.key }
    }

    override suspend fun setAccentCustomColor(color: Int) {
        context.dataStore.edit { prefs -> prefs[ACCENT_CUSTOM_COLOR] = color }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE] = mode.key }
    }

    override suspend fun setUse24HourClock(use24Hour: Boolean) {
        context.dataStore.edit { prefs -> prefs[USE_24H_CLOCK] = use24Hour }
    }

    override suspend fun setOsmMapsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[OSM_MAPS_ENABLED] = enabled }
    }

    companion object {
        /** Stored stand-in for "None" — DataStore has no way to hold a null Int. */
        private const val NO_REMINDER = -1

        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val HIDDEN_CALENDARS = stringSetPreferencesKey("hidden_calendars")
        private val DEFAULT_REMINDER = intPreferencesKey("default_reminder_minutes")
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val ACCENT_CUSTOM_COLOR = intPreferencesKey("accent_custom_color")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val USE_24H_CLOCK = booleanPreferencesKey("use_24h_clock")
        private val OSM_MAPS_ENABLED = booleanPreferencesKey("osm_maps_enabled")
    }
}
