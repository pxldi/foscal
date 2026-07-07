package app.calendarium.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import app.calendarium.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the home screen to redraw the agenda widget. Called whenever the calendar provider
 * changes (so newly created/edited events show up promptly instead of waiting for the next
 * [android.appwidget.AppWidgetProvider] update period).
 */
@Singleton
class WidgetRefresher @Inject constructor(@ApplicationContext private val context: Context) {

    fun refresh() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, AgendaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_event_list)
        AgendaWidgetProvider.triggerUpdate(context, ids)
    }
}
