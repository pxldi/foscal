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

            // List adapter: each instance gets its own factory via a unique data URI so the
            // launcher caches them separately.
            val adapter = Intent(context, AgendaWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_event_list, adapter)
            views.setEmptyView(R.id.widget_event_list, R.id.widget_empty)

            // Header -> open the app at today.
            val openApp = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_header,
                PendingIntent.getActivity(
                    context,
                    REQ_OPEN_APP,
                    openApp,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            // "+" -> quick add.
            val quickAdd = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true)
            views.setOnClickPendingIntent(
                R.id.widget_quick_add,
                PendingIntent.getActivity(
                    context,
                    REQ_QUICK_ADD,
                    quickAdd,
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
                R.id.widget_header_date,
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
            )
            return views
        }

        private const val REQ_OPEN_APP = 100
        private const val REQ_QUICK_ADD = 101
        private const val REQ_OPEN_EVENT = 102
    }
}
