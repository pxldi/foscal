package app.foscal.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.foscal.core.model.AccentColor
import app.foscal.core.model.DayTapAction
import app.foscal.core.model.CalendarReminderDefaults
import app.foscal.core.model.ThemeMode
import java.time.DayOfWeek
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

    override val calendarReminderDefaults: Flow<Map<Long, Int?>> =
        context.dataStore.data.map { prefs ->
            CalendarReminderDefaults.decode(prefs[CALENDAR_REMINDERS].orEmpty())
        }

    override val accentColor: Flow<AccentColor> =
        context.dataStore.data.map { AccentColor.fromKey(it[ACCENT_COLOR]) }

    override val accentCustomColor: Flow<Int> =
        context.dataStore.data.map { it[ACCENT_CUSTOM_COLOR] ?: AccentColor.DEFAULT_CUSTOM_COLOR }

    override val dynamicColor: Flow<Boolean> =
        context.dataStore.data.map { it[DYNAMIC_COLOR] ?: false }

    override val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map { ThemeMode.fromKey(it[THEME_MODE]) }

    override val use24HourClock: Flow<Boolean> =
        context.dataStore.data.map { it[USE_24H_CLOCK] ?: true }

    override val startView: Flow<String> =
        context.dataStore.data.map { it[START_VIEW].orEmpty() }

    override val lastUsedView: Flow<String> =
        context.dataStore.data.map { it[LAST_USED_VIEW].orEmpty() }

    override val firstDayOfWeek: Flow<DayOfWeek> =
        context.dataStore.data.map { prefs ->
            // Stored as ISO 1..7. Anything outside that is a corrupted or future value, and a
            // silent fall back to the default beats throwing on every read of the calendar grid.
            prefs[FIRST_DAY_OF_WEEK]
                ?.takeIf { it in 1..7 }
                ?.let { DayOfWeek.of(it) }
                ?: Preferences.DEFAULT_FIRST_DAY
        }

    override val defaultEventMinutes: Flow<Int> =
        context.dataStore.data.map {
            it[DEFAULT_EVENT_MINUTES]?.takeIf { m -> m > 0 } ?: Preferences.DEFAULT_EVENT_MINUTES
        }

    override val showWeekNumbers: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_WEEK_NUMBERS] ?: false }

    override val dayTapAction: Flow<DayTapAction> =
        context.dataStore.data.map { DayTapAction.fromName(it[DAY_TAP_ACTION]) }

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

    override suspend fun setCalendarReminderDefault(calendarId: Long, minutes: Int?) {
        editCalendarDefaults { it + (calendarId to minutes) }
    }

    override suspend fun clearCalendarReminderDefault(calendarId: Long) {
        editCalendarDefaults { it - calendarId }
    }

    /**
     * Read-modify-write inside a single `edit`, which DataStore serialises against every other
     * writer. Doing it as a `first()` followed by a separate write would let two rows edited in
     * quick succession each start from the same map and the later one erase the earlier.
     */
    private suspend fun editCalendarDefaults(mutate: (Map<Long, Int?>) -> Map<Long, Int?>) {
        context.dataStore.edit { prefs ->
            val current = CalendarReminderDefaults.decode(prefs[CALENDAR_REMINDERS].orEmpty())
            prefs[CALENDAR_REMINDERS] = CalendarReminderDefaults.encode(mutate(current))
        }
    }

    override suspend fun setAccentColor(accent: AccentColor) {
        context.dataStore.edit { prefs -> prefs[ACCENT_COLOR] = accent.key }
    }

    override suspend fun setAccentCustomColor(color: Int) {
        context.dataStore.edit { prefs -> prefs[ACCENT_CUSTOM_COLOR] = color }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE] = mode.key }
    }

    override suspend fun setStartView(view: String) {
        context.dataStore.edit { prefs -> prefs[START_VIEW] = view }
    }

    override suspend fun setLastUsedView(view: String) {
        context.dataStore.edit { prefs -> prefs[LAST_USED_VIEW] = view }
    }

    override suspend fun setFirstDayOfWeek(day: DayOfWeek) {
        context.dataStore.edit { prefs -> prefs[FIRST_DAY_OF_WEEK] = day.value }
    }

    override suspend fun setDefaultEventMinutes(minutes: Int) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_EVENT_MINUTES] = minutes }
    }

    override suspend fun setShowWeekNumbers(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_WEEK_NUMBERS] = enabled }
    }

    override suspend fun setDayTapAction(action: DayTapAction) {
        context.dataStore.edit { prefs -> prefs[DAY_TAP_ACTION] = action.name }
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
        private val CALENDAR_REMINDERS = stringSetPreferencesKey("calendar_reminder_defaults")
        private val ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val ACCENT_CUSTOM_COLOR = intPreferencesKey("accent_custom_color")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val USE_24H_CLOCK = booleanPreferencesKey("use_24h_clock")
        private val OSM_MAPS_ENABLED = booleanPreferencesKey("osm_maps_enabled")
        private val START_VIEW = stringPreferencesKey("start_view")
        private val LAST_USED_VIEW = stringPreferencesKey("last_used_view")
        private val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week")
        private val DEFAULT_EVENT_MINUTES = intPreferencesKey("default_event_minutes")
        private val SHOW_WEEK_NUMBERS = booleanPreferencesKey("show_week_numbers")
        private val DAY_TAP_ACTION = stringPreferencesKey("day_tap_action")
    }
}
