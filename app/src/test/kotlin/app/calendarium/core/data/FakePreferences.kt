package app.calendarium.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [Preferences] for view-model tests. */
class FakePreferences(
    onboardingDone: Boolean = true,
    hidden: Set<String> = emptySet(),
    private val defaultReminder: Int? = 15,
) : Preferences {

    override val onboardingCompleted: Flow<Boolean> = MutableStateFlow(onboardingDone)
    override val hiddenCalendarIds: Flow<Set<String>> = MutableStateFlow(hidden)
    override val defaultReminderMinutes: Flow<Int?> = MutableStateFlow(defaultReminder)

    override suspend fun setOnboardingCompleted() = Unit
    override suspend fun setHiddenCalendars(ids: Set<String>) = Unit
    override suspend fun setDefaultReminder(minutes: Int?) = Unit
}
