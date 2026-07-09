package app.foscal.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Result of parsing a free-text quick-add string. `null` [date] or [time] means the user did not
 * specify one; the caller chooses a sensible default (e.g. today / next hour).
 */
data class QuickAddResult(
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val allDay: Boolean = false,
)

/**
 * Lightweight natural-language parser for the quick-add box. It recognises a date phrase
 * (today / tomorrow / a weekday / "next <weekday>"), a time (12h with am/pm, 24h `HH:MM`,
 * "noon", "midnight"), and an optional "all day" marker; whatever remains becomes the title.
 *
 * It deliberately errs on the side of *not* matching: bare numbers without am/pm or a colon are
 * left in the title so phrases like "2 tickets" aren't misread as a time.
 */
object QuickAddParser {

    private val time12 = Regex("""(?i)\b(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?\b""")
    private val time24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val allDayPhrase = Regex("""(?i)\ball[\s-]?day\b""")

    fun parse(input: String, today: LocalDate = LocalDate.now()): QuickAddResult {
        var text = input.trim()
        val allDay = allDayPhrase.containsMatchIn(text)
        if (allDay) text = allDayPhrase.replace(text, " ")

        val time = findTime(text)?.also { text = text.replace(it.first, " ") }?.second
        val dateMatch = findDate(text, today)?.also { text = text.replace(it.first, " ") }?.second

        val title = text.replace(Regex("\\s+"), " ").trim().ifBlank { "(Untitled)" }
        return QuickAddResult(title = title, date = dateMatch, time = time, allDay = allDay)
    }

    private fun findTime(text: String): Pair<String, LocalTime>? {
        time12.find(text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            val h = ((hour % 12) + if (pm) 12 else 0)
            return m.value to LocalTime.of(h, minute)
        }
        time24.find(text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            return m.value to LocalTime.of(hour, minute)
        }
        Regex("""(?i)\bnoon\b""").find(text)?.let {
            return it.value to LocalTime.of(12, 0)
        }
        Regex("""(?i)\bmidnight\b""").find(text)?.let {
            return it.value to LocalTime.of(0, 0)
        }
        return null
    }

    private fun findDate(text: String, today: LocalDate): Pair<String, LocalDate>? {
        Regex("""(?i)\btoday\b""").find(text)?.let { return it.value to today }
        Regex("""(?i)\b(tomorrow|tmrw)\b""").find(text)?.let { return it.value to today.plusDays(1) }

        // "next <weekday>" -> the first occurrence strictly after today.
        val next = Regex("""(?i)\bnext\s+($weekdayPattern)\b""").find(text)
        if (next != null) {
            val day = codeToDay(next.groupValues[1].take(3)) ?: return next.value to today
            return next.value to nextAfter(today, day, strict = true)
        }

        // A bare weekday -> the first occurrence on or after today (today if already that day).
        val bare = Regex("""(?i)\b($weekdayPattern)\b""").find(text)
        if (bare != null) {
            val day = codeToDay(bare.groupValues[1].take(3)) ?: return null
            return bare.value to nextAfter(today, day, strict = false)
        }
        return null
    }

    // Full names first so the alternation is greedy on the whole word; the \b anchor then stops
    // "monthly" from matching "mon" while still letting "monday" and the bare abbreviations match.
    private const val weekdayPattern =
        "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun"

    private fun codeToDay(code: String): DayOfWeek? = when (code.lowercase()) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun nextAfter(today: LocalDate, target: DayOfWeek, strict: Boolean): LocalDate {
        var d = today
        if (strict) d = d.plusDays(1)
        while (d.dayOfWeek != target) d = d.plusDays(1)
        return d
    }
}
