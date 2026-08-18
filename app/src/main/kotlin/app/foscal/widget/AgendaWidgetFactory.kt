package app.foscal.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.foscal.MainActivity
import app.foscal.R
import app.foscal.core.model.Event
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
        val use24Hour = prefs.use24HourClock.first()
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
                subtitle = subtitleFor(e, today, zone, use24Hour),
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
            Intent()
                .putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, row.eventId)
                .putExtra(MainActivity.EXTRA_OPEN_INSTANCE_START, row.instanceStart),
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getCount(): Int = rows.size
    override fun onDestroy() = Unit

    private fun subtitleFor(event: Event, today: LocalDate, zone: ZoneId, use24Hour: Boolean): String {
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
            val pattern = if (use24Hour) "HH:mm" else "h:mm a"
            event.start.atZone(zone).toLocalTime()
                .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
        // Location last, on the same separator as the rest: it is the thing you check second,
        // after "when", and it is also the part most likely to be missing.
        val place = event.location?.trim().orEmpty()
        return listOf(dayLabel, timeLabel, place)
            .filter { it.isNotBlank() }
            .joinToString("  •  ")
    }

    companion object {
        /**
         * How far ahead to look, and how much of it to show.
         *
         * The widget answers "what is next", so the row cap is the real limit and the horizon is
         * only there to stop an unbounded query. A fortnight was too short for a sparse calendar —
         * it left the widget empty while there were perfectly good events three weeks out.
         */
        private const val HORIZON_DAYS = 90L
        private const val MAX_ROWS = 20
        private const val DAYS_AS_WEEKDAY = 7L
    }
}
