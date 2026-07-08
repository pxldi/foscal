package app.calendarium.core.data

import app.calendarium.core.model.AccentColor
import app.calendarium.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the user's preferences. [UserPreferencesRepository] is the production
 * implementation (DataStore-backed); tests supply a simple in-memory fake.
 */
interface Preferences {
    val onboardingCompleted: Flow<Boolean>
    val hiddenCalendarIds: Flow<Set<String>>
    val defaultReminderMinutes: Flow<Int?>
    val accentColor: Flow<AccentColor>
    val themeMode: Flow<ThemeMode>
    val use24HourClock: Flow<Boolean>

    /**
     * Whether the user has opted into the OpenStreetMap-backed location picker. Off by default —
     * the only feature that uses the network, so it stays disabled until the user turns it on
     * (during onboarding or in Settings).
     */
    val osmMapsEnabled: Flow<Boolean>

    suspend fun setOnboardingCompleted()
    suspend fun setHiddenCalendars(ids: Set<String>)
    suspend fun setDefaultReminder(minutes: Int?)
    suspend fun setAccentColor(accent: AccentColor)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUse24HourClock(use24Hour: Boolean)
    suspend fun setOsmMapsEnabled(enabled: Boolean)
}
