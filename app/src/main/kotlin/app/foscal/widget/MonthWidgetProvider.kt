package app.foscal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.foscal.MainActivity
import app.foscal.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * A month grid on the home screen: which day it is, and which days have something on them.
 *
 * Built with `addView` rather than a layout of 42 pre-declared cells because the grid is not always
 * 42 — a month spans four, five or six weeks, and a fixed grid would either pad every month to six
 * rows or need six layouts. `RemoteViews` cannot lay out a real grid, so each week is a row view
 * with seven equally weighted cells added into it.
 *
 * Deliberately not interactive beyond opening the app. A widget that tries to page through months
 * needs a `PendingIntent` per arrow and its own persisted position, and at that point it is a worse
 * copy of the app rather than a glance at it.
 */
class MonthWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
    }

    companion object {

        fun triggerUpdate(context: Context, appWidgetIds: IntArray) {
            val intent = Intent(context, MonthWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
        }

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_month)
            val locale = Locale.getDefault()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val month = YearMonth.from(today)
            val grid = runBlocking { loadGrid(context, month, zone) }

            views.setTextViewText(
                R.id.widget_month_title,
                "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
            )
            views.setOnClickPendingIntent(
                R.id.widget_month_header,
                activity(context, REQ_OPEN_APP, Intent(context, MainActivity::class.java)),
            )
            views.setOnClickPendingIntent(
                R.id.widget_month_add,
                activity(
                    context,
                    REQ_QUICK_ADD,
                    Intent(context, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_OPEN_QUICK_ADD, true),
                ),
            )
            // The grid itself opens the app rather than a specific day: a home-screen cell is far
            // too small a target to trust with "which day did I mean".
            views.setOnClickPendingIntent(
                R.id.widget_month_grid,
                activity(context, REQ_OPEN_APP, Intent(context, MainActivity::class.java)),
            )

            views.removeAllViews(R.id.widget_month_weekdays)
            repeat(7) { offset ->
                val day = grid.firstDayOfWeek.plus(offset.toLong())
                val label = RemoteViews(context.packageName, R.layout.widget_month_weekday)
                label.setTextViewText(
                    R.id.widget_weekday_label,
                    day.getDisplayName(TextStyle.NARROW, locale),
                )
                views.addView(R.id.widget_month_weekdays, label)
            }

            views.removeAllViews(R.id.widget_month_grid)
            grid.dates.chunked(7).forEach { week ->
                val row = RemoteViews(context.packageName, R.layout.widget_month_row)
                week.forEach { date ->
                    row.addView(R.id.widget_month_row, cell(context, date, month, today, grid))
                }
                views.addView(R.id.widget_month_grid, row)
            }
            return views
        }

        private fun cell(
            context: Context,
            date: LocalDate,
            month: YearMonth,
            today: LocalDate,
            grid: MonthGrid,
        ): RemoteViews {
            val cell = RemoteViews(context.packageName, R.layout.widget_month_cell)
            cell.setTextViewText(R.id.widget_cell_day, date.dayOfMonth.toString())
            val color = when {
                date == today -> context.getColor(R.color.widget_accent)
                YearMonth.from(date) == month -> context.getColor(R.color.widget_title)
                else -> context.getColor(R.color.widget_subtitle)
            }
            cell.setTextColor(R.id.widget_cell_day, color)
            val dotColor = grid.colors[date]
            if (dotColor == null) {
                // Hidden rather than absent, so every cell keeps the same height and the rows stay
                // aligned whether or not that week has any events in it.
                cell.setViewVisibility(R.id.widget_cell_dot, android.view.View.INVISIBLE)
            } else {
                cell.setViewVisibility(R.id.widget_cell_dot, android.view.View.VISIBLE)
                cell.setInt(R.id.widget_cell_dot, "setColorFilter", dotColor)
            }
            return cell
        }

        private data class MonthGrid(
            val firstDayOfWeek: DayOfWeek,
            val dates: List<LocalDate>,
            /** The colour of the first event on each day that has one. */
            val colors: Map<LocalDate, Int>,
        )

        private suspend fun loadGrid(
            context: Context,
            month: YearMonth,
            zone: ZoneId,
        ): MonthGrid {
            val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
            val repo = entry.calendarRepository()
            val prefs = entry.userPreferencesRepository()
            val firstDay = prefs.firstDayOfWeek.first()
            val dates = monthCells(month, firstDay)

            val hidden = prefs.hiddenCalendarIds.first()
            val ids = repo.getCalendars()
                .filter { it.visible && it.id.toString() !in hidden }
                .map { it.id }
                .toSet()
            if (ids.isEmpty()) return MonthGrid(firstDay, dates, emptyMap())

            val from = dates.first().atStartOfDay(zone).toInstant()
            val to = dates.last().plusDays(1).atStartOfDay(zone).toInstant()
            // First event of the day wins the dot. `toMap` would keep the last one, which on a
            // day with a morning meeting and an evening thing is the less useful of the two.
            val colors = repo.getEvents(ids, from, to)
                .sortedBy { it.start }
                .flatMap { event -> event.spannedDays(zone).map { day -> day to event.color } }
                .fold(mutableMapOf<LocalDate, Int>()) { acc, (day, color) ->
                    acc.apply { putIfAbsent(day, color) }
                }
            return MonthGrid(firstDay, dates, colors)
        }

        /** Whole weeks covering [month], starting on [firstDayOfWeek]. Four, five or six of them. */
        private fun monthCells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate> {
            val first = month.atDay(1)
            val offset = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
            val origin = first.minusDays(offset.toLong())
            val weeks = ((offset + month.lengthOfMonth()) + 6) / 7
            return (0 until weeks * 7).map { origin.plusDays(it.toLong()) }
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

        private const val REQ_OPEN_APP = 200
        private const val REQ_QUICK_ADD = 201
    }
}
