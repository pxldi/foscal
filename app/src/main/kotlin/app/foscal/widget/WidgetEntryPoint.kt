package app.foscal.widget

import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.UserPreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point used by the app-widget components (an [android.content.BroadcastReceiver] and
 * a [android.widget.RemoteViewsService]), which can't use `@AndroidEntryPoint`. Reached via
 * `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun calendarRepository(): CalendarRepository
    fun userPreferencesRepository(): UserPreferencesRepository
}
