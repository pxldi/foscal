package app.foscal.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The five ways to look at your events.
 *
 * Not navigation destinations — lenses on the same data. Day, 3 Days and Week are literally the
 * same timeline with a different [timelineDays]; Month and Agenda are their own layouts. They live
 * together in one switcher because choosing between them is a single decision.
 */
enum class CalendarView(val label: String, val timelineDays: Int?) {
    Agenda("Agenda", null),
    Day("Day", 1),
    ThreeDay("3 Days", 3),
    Week("Week", 7),
    Month("Month", null),
}

/**
 * An icon that *is* the layout it selects: one pane for Day, three for 3 Days, a grid for Month.
 *
 * Drawn rather than taken from the Material set because no stock icon distinguishes three columns
 * from seven, and that distinction is the entire point of the switcher — you should be able to
 * tell the views apart without reading the labels.
 */
@Composable
fun ViewGlyph(view: CalendarView, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        when (view) {
            CalendarView.Agenda -> drawAgenda(tint, stroke)
            CalendarView.Day -> drawPanes(tint, stroke, columns = 1)
            CalendarView.ThreeDay -> drawPanes(tint, stroke, columns = 3)
            CalendarView.Week -> drawPanes(tint, stroke, columns = 7)
            CalendarView.Month -> drawPanes(tint, stroke, columns = 4, rows = 4)
        }
    }
}

/** A frame divided into [columns] and [rows]; one column and one row is just an empty page. */
private fun DrawScope.drawPanes(tint: Color, stroke: Float, columns: Int, rows: Int = 1) {
    val inset = stroke / 2f
    val width = size.width - stroke
    val height = size.height - stroke
    drawRoundRect(
        color = tint,
        topLeft = Offset(inset, inset),
        size = Size(width, height),
        cornerRadius = CornerRadius(2.5.dp.toPx()),
        style = Stroke(stroke),
    )
    // Seven panes at the frame's own stroke close up into a solid block at icon size, so the
    // week's dividers are drawn finer.
    val divider = if (columns > 4) stroke * 0.55f else stroke
    for (i in 1 until columns) {
        val x = inset + width * i / columns
        drawLine(tint, Offset(x, inset), Offset(x, inset + height), divider)
    }
    for (i in 1 until rows) {
        val y = inset + height * i / rows
        drawLine(tint, Offset(inset, y), Offset(inset + width, y), stroke)
    }
}

/** Three rows of bullet-and-line: a list, not a grid. */
private fun DrawScope.drawAgenda(tint: Color, stroke: Float) {
    val dot = size.width * 0.09f
    repeat(3) { row ->
        val y = size.height * (0.22f + row * 0.28f)
        drawCircle(tint, dot, Offset(dot, y))
        drawLine(tint, Offset(dot * 3f, y), Offset(size.width, y), stroke)
    }
}
