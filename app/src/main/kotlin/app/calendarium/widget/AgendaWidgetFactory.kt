package app.calendarium.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.calendarium.MainActivity
import app.calendarium.R
import app.calendarium.core.model.Event
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class AgendaWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val eventId: Long,
        val instanceStart: Long,
        val title: String,
        val subtitle: String,
        val color: Int,
    )

    private val rows = mutableListOf<Row>()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows.clear()
        rows += runBlocking { loadRows() }
    }

    private suspend fun loadRows(): List<Row> {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repo = entry.calendarRepository()
        val prefs = entry.userPreferencesRepository()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val hidden = prefs.hiddenCalendarIds.first()
        val ids = repo.getCalendars()
            .filter { it.visible && it.id.toString() !in hidden }
            .map { it.id }
            .toSet()
        if (ids.isEmpty()) return emptyList()
        val from = Instant.now()
        val to = today.plusDays(HORIZON_DAYS).atStartOfDay(zone).toInstant()
        val events = repo.getEvents(ids, from, to).sortedBy { it.start }
        return events.take(MAX_ROWS).map { e ->
            Row(
                eventId = e.id,
                instanceStart = e.start.toEpochMilli(),
                title = e.title,
                subtitle = subtitleFor(e, today, zone),
                color = e.color,
            )
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_agenda_item)
        views.setTextViewText(R.id.widget_item_title, row.title)
        views.setTextViewText(R.id.widget_item_subtitle, row.subtitle)
        views.setInt(R.id.widget_item_color, "setColorFilter", row.color)
        views.setOnClickFillInIntent(
            R.id.widget_item_root,
            Intent().putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, row.eventId),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getCount(): Int = rows.size
    override fun onDestroy() = Unit

    private fun subtitleFor(event: Event, today: LocalDate, zone: ZoneId): String {
        val day = if (event.allDay) {
            event.start.atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            event.start.atZone(zone).toLocalDate()
        }
        val dayLabel = when {
            day == today -> ""
            day == today.plusDays(1) -> "Tomorrow"
            day.isBefore(today.plusDays(DAYS_AS_WEEKDAY)) ->
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            else -> day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }
        val timeLabel = if (event.allDay) {
            "All day"
        } else {
            event.start.atZone(zone).toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        }
        return listOf(dayLabel, timeLabel).filter { it.isNotBlank() }.joinToString("  •  ")
    }

    companion object {
        private const val HORIZON_DAYS = 14L
        private const val MAX_ROWS = 20
        private const val DAYS_AS_WEEKDAY = 7L
    }
}
