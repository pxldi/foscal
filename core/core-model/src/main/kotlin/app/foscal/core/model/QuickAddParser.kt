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

    /**
     * [use24Hour] is the user's clock setting. With the 12-hour clock a bare "3:30" means the
     * afternoon; see [findTime].
     */
    fun parse(
        input: String,
        today: LocalDate = LocalDate.now(),
        use24Hour: Boolean = true,
    ): QuickAddResult {
        var text = input.trim()
        val allDay = allDayPhrase.containsMatchIn(text)
        if (allDay) text = allDayPhrase.replace(text, " ")

        val time = findTime(text, use24Hour)?.also { text = text.replaceRange(it.first, " ") }?.second
        val dateMatch = findDate(text, today)?.also { text = text.replaceRange(it.first, " ") }?.second

        val title = text.replace(Regex("\\s+"), " ").trim().ifBlank { "(Untitled)" }
        return QuickAddResult(title = title, date = dateMatch, time = time, allDay = allDay)
    }

    // Each finder skips a match whose numbers are out of range and keeps looking. The parser runs
    // on every keystroke inside composition, so an impossible time such as "3:75pm" must leave the
    // text alone rather than reach LocalTime.of and throw.
    private fun findTime(text: String, use24Hour: Boolean): Pair<IntRange, LocalTime>? {
        time12.findAll(text).forEach { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            if (hour !in 1..12 || minute !in 0..59) return@forEach
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            val h = ((hour % 12) + if (pm) 12 else 0)
            return m.range to LocalTime.of(h, minute)
        }
        time24.find(text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            return m.range to LocalTime.of(if (use24Hour) hour else twelveHourGuess(m.groupValues[1]), minute)
        }
        Regex("""(?i)\bnoon\b""").find(text)?.let {
            return it.range to LocalTime.of(12, 0)
        }
        Regex("""(?i)\bmidnight\b""").find(text)?.let {
            return it.range to LocalTime.of(0, 0)
        }
        return null
    }

    /**
     * The hour a 12-hour user means by a bare "h:mm". 1 to 6 is the afternoon, because nobody
     * adds a 3:30 call at night; 7 to 11 is the morning and 12 is noon. An hour of 13 or more, or
     * one written with a leading zero, is already 24-hour and is read as written.
     */
    private fun twelveHourGuess(written: String): Int {
        val hour = written.toInt()
        return if (written.startsWith("0") || hour !in 1..6) hour else hour + 12
    }

    private fun findDate(text: String, today: LocalDate): Pair<IntRange, LocalDate>? {
        Regex("""(?i)\btoday\b""").find(text)?.let { return it.range to today }
        Regex("""(?i)\b(tomorrow|tmrw)\b""").find(text)?.let { return it.range to today.plusDays(1) }

        // "next <weekday>" -> the first occurrence strictly after today.
        val next = Regex("""(?i)\bnext\s+($weekdayPattern)\b""").find(text)
        if (next != null) {
            val day = codeToDay(next.groupValues[1].take(3)) ?: return next.range to today
            return next.range to nextAfter(today, day, strict = true)
        }

        // A bare weekday -> the first occurrence on or after today (today if already that day).
        Regex("""(?i)\b($weekdayPattern)\b""").findAll(text).forEach { m ->
            if (!readsAsWeekday(m, text)) return@forEach
            val day = codeToDay(m.groupValues[1].take(3)) ?: return@forEach
            return m.range to nextAfter(today, day, strict = false)
        }
        return null
    }

    /**
     * Whether a bare weekday match means a day. An abbreviation in capitals is an acronym ("SAT
     * prep"). "sat", "sun" and "wed" are also English words, so one followed by a capitalised
     * word is read as the start of a name ("Sun Valley"). "next" in front removes both doubts,
     * which is why [findDate] does not apply this to "next <weekday>".
     */
    private fun readsAsWeekday(match: MatchResult, text: String): Boolean {
        val word = match.value
        if (word.length > 3 && word.lowercase() !in abbreviations) return true
        if (word.length > 1 && word == word.uppercase()) return false
        if (word.lowercase() !in ordinaryWords) return true
        val following = text.substring(match.range.last + 1).trimStart()
        return following.firstOrNull()?.isUpperCase() != true
    }

    private val abbreviations = setOf("tues", "thur", "thurs")
    private val ordinaryWords = setOf("sat", "sun", "wed")

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
