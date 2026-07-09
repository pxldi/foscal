package app.foscal.core.data

import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
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
    /** ARGB seed color used when [accentColor] is [AccentColor.CUSTOM]. */
    val accentCustomColor: Flow<Int>
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
    suspend fun setAccentCustomColor(color: Int)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUse24HourClock(use24Hour: Boolean)
    suspend fun setOsmMapsEnabled(enabled: Boolean)
}
