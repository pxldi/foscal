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
        /** Blank on the second and later events of a day, so the date is written once per day. */
        val weekday: String,
        val day: String,
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
        val locale = Locale.getDefault()
        val today = LocalDate.now(zone)
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

        var lastDay: LocalDate? = null
        return events.take(MAX_ROWS).map { e ->
            // The day an event *starts* on, except for one already running, which belongs under
            // today — it is what is on now, not what happened last Tuesday.
            val day = e.startLocalDate(zone).coerceAtLeast(today)
            val repeat = day == lastDay
            lastDay = day
            Row(
                eventId = e.id,
                instanceStart = e.start.toEpochMilli(),
                title = e.title,
                subtitle = subtitleFor(e, zone, use24Hour, locale),
                color = e.color,
                weekday = if (repeat) "" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                day = if (repeat) "" else day.dayOfMonth.toString(),
            )
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows[position]
        val views = RemoteViews(context.packageName, R.layout.widget_agenda_item)
        views.setTextViewText(R.id.widget_item_weekday, row.weekday)
        views.setTextViewText(R.id.widget_item_day, row.day)
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

    /**
     * When the event runs, and where. The day itself is not repeated here — it is already written
     * in the gutter to the left of this line.
     */
    private fun subtitleFor(
        event: Event,
        zone: ZoneId,
        use24Hour: Boolean,
        locale: Locale,
    ): String {
        val first = event.startLocalDate(zone)
        val last = event.lastLocalDate(zone).coerceAtLeast(first)
        val dayFmt = DateTimeFormatter.ofPattern("MMM d", locale)
        val timeFmt = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)

        val whenLabel = when {
            event.allDay && first == last -> "All day"
            event.allDay -> "All day until ${last.format(dayFmt)}"
            first == last -> {
                val start = event.start.atZone(zone).toLocalTime().format(timeFmt)
                val end = event.end.atZone(zone).toLocalTime().format(timeFmt)
                "$start - $end"
            }
            else -> {
                val start = event.start.atZone(zone).toLocalTime().format(timeFmt)
                "$start until ${last.format(dayFmt)}"
            }
        }
        // Location last, on the same separator as the rest: it is the thing you check second,
        // after "when", and it is also the part most likely to be missing.
        val place = event.location?.trim().orEmpty()
        return listOf(whenLabel, place).filter { it.isNotBlank() }.joinToString("  •  ")
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
        private const val MAX_ROWS = 40
    }
}
