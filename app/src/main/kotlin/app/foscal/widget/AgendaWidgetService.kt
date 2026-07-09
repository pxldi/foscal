package app.foscal.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Backs the widget's event list. Returns a [RemoteViewsFactory] scoped to the calling widget id.
 */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsService.RemoteViewsFactory =
        AgendaWidgetFactory(applicationContext)
}
