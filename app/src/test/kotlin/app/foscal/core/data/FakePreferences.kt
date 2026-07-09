package app.foscal.core.data

import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [Preferences] for view-model tests. */
class FakePreferences(
    onboardingDone: Boolean = true,
    hidden: Set<String> = emptySet(),
    private val defaultReminder: Int? = 15,
    accent: AccentColor = AccentColor.COBALT,
    theme: ThemeMode = ThemeMode.SYSTEM,
    use24Hour: Boolean = true,
    osmMaps: Boolean = false,
) : Preferences {

    override val onboardingCompleted: Flow<Boolean> = MutableStateFlow(onboardingDone)
    override val hiddenCalendarIds: Flow<Set<String>> = MutableStateFlow(hidden)
    override val defaultReminderMinutes: Flow<Int?> = MutableStateFlow(defaultReminder)
    override val accentColor: MutableStateFlow<AccentColor> = MutableStateFlow(accent)
    override val accentCustomColor: MutableStateFlow<Int> =
        MutableStateFlow(AccentColor.DEFAULT_CUSTOM_COLOR)
    override val themeMode: MutableStateFlow<ThemeMode> = MutableStateFlow(theme)
    override val use24HourClock: MutableStateFlow<Boolean> = MutableStateFlow(use24Hour)
    override val osmMapsEnabled: MutableStateFlow<Boolean> = MutableStateFlow(osmMaps)

    override suspend fun setOnboardingCompleted() = Unit
    override suspend fun setHiddenCalendars(ids: Set<String>) = Unit
    override suspend fun setDefaultReminder(minutes: Int?) = Unit
    override suspend fun setAccentColor(accent: AccentColor) {
        accentColor.value = accent
    }

    override suspend fun setAccentCustomColor(color: Int) {
        accentCustomColor.value = color
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override suspend fun setUse24HourClock(use24Hour: Boolean) {
        use24HourClock.value = use24Hour
    }

    override suspend fun setOsmMapsEnabled(enabled: Boolean) {
        osmMapsEnabled.value = enabled
    }
}
