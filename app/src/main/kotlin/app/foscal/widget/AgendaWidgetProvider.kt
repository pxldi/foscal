package app.foscal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import app.foscal.MainActivity
import app.foscal.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class AgendaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context, id))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            // The rows come from a RemoteViewsFactory, which re-reads the calendar only when it is
            // told the data changed. Redrawing the widget alone would refresh the date in the
            // header and leave yesterday's events under it.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager?.getAppWidgetIds(ComponentName(context, javaClass))
            if (ids != null && ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_event_list)
                ids.forEach { manager.updateAppWidget(it, buildRemoteViews(context, it)) }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        /** Broadcasts an ACTION_APPWIDGET_UPDATE so all instances redraw their list and header. */
        fun triggerUpdate(context: Context, appWidgetIds: IntArray) {
            val intent = Intent(context, AgendaWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
        }

        fun buildRemoteViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_agenda)
            val locale = Locale.getDefault()
            val today = LocalDate.now()

            // List adapter: each instance gets its own factory via a unique data URI so the
            // launcher caches them separately.
            val adapter = Intent(context, AgendaWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_event_list, adapter)
            views.setEmptyView(R.id.widget_event_list, R.id.widget_empty)

            // The date opens the app; the two icons beside it are their own targets, so the tap
            // area is the title block rather than the whole header row.
            views.setOnClickPendingIntent(
                R.id.widget_header_titles,
                activity(context, REQ_OPEN_APP, Intent(context, MainActivity::class.java)),
            )

            views.setOnClickPendingIntent(
                R.id.widget_quick_add,
                activity(
                    context,
                    REQ_QUICK_ADD,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true),
                ),
            )

            // A widget updates on its own every half hour at best, and a calendar changed on
            // another device arrives whenever it arrives. Refresh is the escape hatch for the gap.
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(
                    context,
                    REQ_REFRESH,
                    Intent(context, AgendaWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            // List rows: a template PendingIntent that each row fills with its event id. It must
            // be MUTABLE so the launcher can attach each row's fill-in Intent.
            val rowTemplate = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setPendingIntentTemplate(
                R.id.widget_event_list,
                PendingIntent.getActivity(
                    context,
                    REQ_OPEN_EVENT,
                    rowTemplate,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            views.setTextViewText(
                R.id.widget_header_weekday,
                today.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
            )
            views.setTextViewText(
                R.id.widget_header_date,
                today.format(DateTimeFormatter.ofPattern("MMMM d", locale)),
            )
            return views
        }

        private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private const val ACTION_REFRESH = "app.foscal.widget.action.REFRESH_AGENDA"
        private const val REQ_OPEN_APP = 100
        private const val REQ_QUICK_ADD = 101
        private const val REQ_OPEN_EVENT = 102
        private const val REQ_REFRESH = 103
    }
}
