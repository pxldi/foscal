package app.foscal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import app.foscal.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the home screen to redraw Foscal's widgets. Called whenever the calendar provider
 * changes (so newly created/edited events show up promptly instead of waiting for the next
 * [android.appwidget.AppWidgetProvider] update period).
 */
@Singleton
class WidgetRefresher @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun refresh() {
        val manager = AppWidgetManager.getInstance(context) ?: return

        val agenda = manager.getAppWidgetIds(ComponentName(context, AgendaWidgetProvider::class.java))
        if (agenda.isNotEmpty()) {
            // The list is served by a RemoteViewsFactory, which only re-reads the calendar when it
            // is told the data changed; redrawing the widget alone would leave the old rows.
            manager.notifyAppWidgetViewDataChanged(agenda, R.id.widget_event_list)
            AgendaWidgetProvider.triggerUpdate(context, agenda)
        }

        val month = manager.getAppWidgetIds(ComponentName(context, MonthWidgetProvider::class.java))
        if (month.isNotEmpty()) MonthWidgetProvider.triggerUpdate(context, month)
    }
}
