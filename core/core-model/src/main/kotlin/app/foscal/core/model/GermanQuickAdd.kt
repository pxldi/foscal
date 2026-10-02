package app.foscal.core.model

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The German half of [QuickAddParser]: "Zahnarzt Freitag 9:30", "morgen 14 Uhr", "übermorgen",
 * "nächsten Montag", "um halb drei", "vom 19. bis 23. Okt", "von 14 bis 16 Uhr".
 *
 * Every pattern is built with [RegexOption.IGNORE_CASE] rather than an inline `(?i)`: Kotlin adds
 * Unicode case folding to the option, so "Übermorgen" matches "übermorgen", while `(?i)` folds ASCII
 * only. Word edges are lookarounds on letters for the same reason; `\b` does not treat "ü" as a
 * letter on every runtime, and the inline flags that would make it (`(?U)`) do not exist on Android.
 */
internal object GermanQuickAdd {

    class DateMatch(val range: IntRange, val date: LocalDate, val endDate: LocalDate? = null)

    class TimeMatch(val range: IntRange, val time: LocalTime, val endTime: LocalTime? = null)

    private const val START = """(?<![\p{L}\p{N}])"""
    private const val END = """(?![\p{L}\p{N}])"""

    private fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    val allDayPhrase = regex("""${START}(?:ganzt[äa]gig\p{L}*|ganztags|den\s+ganzen\s+tag)$END""")

    // ---- Times ------------------------------------------------------------------------------

    private const val NUMBER_WORDS =
        "eins|zwei|drei|vier|fünf|fuenf|sechs|sieben|acht|neun|zehn|elf|zwölf|zwoelf"

    private const val DAY_PART = "morgens|früh|frueh|vormittags|mittags|nachmittags|abends|nachts"

    /** "halb drei", "viertel nach 3", "viertel vor drei", and the regional "(drei)viertel drei". */
    private val spokenTime = regex(
        """$START(?:um\s+)?(halb|viertel\s+nach|viertel\s+vor|dreiviertel|viertel)\s+""" +
            """($NUMBER_WORDS|\d{1,2})$END(?:\s+uhr$END)?(?:\s+($DAY_PART)$END)?""",
    )

    /** "von 14 bis 16 Uhr", "14:00-15:30", "9.30 – 11 Uhr". */
    private val timeRange = regex(
        """$START((?:von|um)\s+)?(\d{1,2})(?:([:.])(\d{2}))?(?:\s*uhr)?\s*(?:bis|-|–)\s*""" +
            """(\d{1,2})(?:([:.])(\d{2}))?(\s*uhr$END)?(?!\.?\d)""",
    )

    /** "14 Uhr", "um 9:30 Uhr", "acht Uhr abends". */
    private val clockTime = regex(
        """$START(?:um\s+)?(\d{1,2}|$NUMBER_WORDS)(?:[:.](\d{2}))?\s*uhr$END(?:\s+($DAY_PART)$END)?""",
    )

    /** "um 9", "um drei", "um 9.30": "um" alone marks a time. */
    private val atTime = regex(
        """${START}um\s+(\d{1,2}|$NUMBER_WORDS)(?:([:.])(\d{2}))?$END(?!\s*(?:%|€|euro|prozent))""" +
            """(?:\s+($DAY_PART)$END)?""",
    )

    private val noon = regex("""$START(?:zu\s+)?mittags?$END""")
    private val midnight = regex("""${START}mitternacht$END""")

    fun findTime(text: String, use24Hour: Boolean): TimeMatch? {
        spokenTime.findAll(text).forEach { m ->
            val n = hourNumber(m.groupValues[2]) ?: return@forEach
            if (n !in 1..12) return@forEach
            val (hour12, minute) = when (m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")) {
                "halb" -> previousHour(n) to 30
                "viertel nach" -> n to 15
                "viertel vor", "dreiviertel" -> previousHour(n) to 45
                else -> previousHour(n) to 15
            }
            return TimeMatch(m.range, LocalTime.of(spokenHour(hour12, m.groupValues[3]), minute))
        }
        timeRange.findAll(text).forEach { m ->
            val hasMarker = m.groupValues[1].isNotEmpty()
            val hasUhr = m.groupValues[8].isNotEmpty()
            // "19.10-23.10" is a pair of dates as easily as a pair of times, so a range needs a
            // colon, "Uhr", "von" or "um" to say it is about the clock.
            val colon = m.groupValues[3] == ":" || m.groupValues[6] == ":"
            if (!hasMarker && !hasUhr && !colon) return@forEach
            val start = writtenTime(m.groupValues[2], m.groupValues[4], use24Hour) ?: return@forEach
            val end = writtenTime(m.groupValues[5], m.groupValues[7], use24Hour) ?: return@forEach
            return TimeMatch(m.range, start, end)
        }
        clockTime.findAll(text).forEach { m ->
            val time = timeOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], use24Hour)
                ?: return@forEach
            return TimeMatch(m.range, time)
        }
        atTime.findAll(text).forEach { m ->
            val time = if (m.groupValues[3].isNotEmpty()) {
                timeOf(m.groupValues[1], m.groupValues[3], m.groupValues[4], use24Hour)
            } else {
                // A bare "um 3" is how people speak, and nobody means three at night by it.
                val n = hourNumber(m.groupValues[1]) ?: return@forEach
                when {
                    n in 1..12 -> LocalTime.of(spokenHour(n, m.groupValues[4]), 0)
                    n in 0..23 -> LocalTime.of(n, 0)
                    else -> null
                }
            } ?: return@forEach
            return TimeMatch(m.range, time)
        }
        noon.find(text)?.let { return TimeMatch(it.range, LocalTime.of(12, 0)) }
        midnight.find(text)?.let { return TimeMatch(it.range, LocalTime.of(0, 0)) }
        return null
    }

    /**
     * A time written with digits: [use24Hour] decides a bare "3:30" exactly as it does in English,
     * unless a day part such as "abends" settles it.
     */
    private fun timeOf(hourText: String, minuteText: String, dayPart: String, use24Hour: Boolean): LocalTime? {
        val hour = hourNumber(hourText) ?: return null
        val minute = minuteText.ifEmpty { "0" }.toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        if (dayPart.isNotEmpty() && hour in 1..12) return LocalTime.of(spokenHour(hour, dayPart), minute)
        val isWord = hourText.firstOrNull()?.isDigit() != true
        return if (isWord) {
            LocalTime.of(spokenHour(hour, ""), minute)
        } else {
            LocalTime.of(if (use24Hour) hour else QuickAddParser.twelveHourGuess(hourText), minute)
        }
    }

    private fun writtenTime(hourText: String, minuteText: String, use24Hour: Boolean): LocalTime? =
        timeOf(hourText, minuteText, "", use24Hour)

    private fun previousHour(n: Int) = if (n == 1) 12 else n - 1

    /**
     * The 24-hour value of [hour12] (1..12) as spoken. A day part decides it; without one, 1 to 6 is
     * the afternoon, as [QuickAddParser.twelveHourGuess] reads a 12-hour clock.
     */
    private fun spokenHour(hour12: Int, dayPart: String): Int {
        val pm = when (dayPart.lowercase()) {
            "morgens", "früh", "frueh", "vormittags" -> false
            "mittags" -> hour12 < 11
            "nachmittags", "abends" -> true
            "nachts" -> hour12 in 7..11
            else -> hour12 in 1..6
        }
        return when {
            hour12 == 12 -> if (dayPart.lowercase() == "nachts") 0 else 12
            pm -> hour12 + 12
            else -> hour12
        }
    }

    private fun hourNumber(text: String): Int? = text.toIntOrNull() ?: when (text.lowercase()) {
        "eins" -> 1
        "zwei" -> 2
        "drei" -> 3
        "vier" -> 4
        "fünf", "fuenf" -> 5
        "sechs" -> 6
        "sieben" -> 7
        "acht" -> 8
        "neun" -> 9
        "zehn" -> 10
        "elf" -> 11
        "zwölf", "zwoelf" -> 12
        else -> null
    }

    // ---- Dates ------------------------------------------------------------------------------

    private const val WEEKDAYS = "montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonnabend|sonntag"
    private const val WEEKDAY_ABBREVIATIONS = "Mo|Di|Mi|Do|Fr|Sa|So"

    // Longest spelling first, so the alternation takes the whole word before the end check.
    private const val MONTHS = "januar|jänner|jaenner|februar|feber|märz|maerz|april|mai|juni|juli|" +
        "august|september|oktober|november|dezember|jän|jaen|jan|feb|mär|mrz|apr|jun|jul|aug|" +
        "sept|sep|okt|nov|dez"

    /** An optional leading weekday, as in "Mo., 19.10." or "Freitag, den 23. Oktober". */
    private const val WEEKDAY_PREFIX =
        """(?:(?:$WEEKDAYS|$WEEKDAY_ABBREVIATIONS)\.?,?\s+(?:den\s+)?)?"""

    /** Day, then month as a number ("19.10.") or a name ("19. Okt"), then an optional year. */
    private const val FULL_DATE =
        """(\d{1,2})\.\s*(?:(\d{1,2})\.(\d{4}|\d{2})?|($MONTHS)$END\.?(?:\s+(\d{4})$END)?)"""

    // A date's day may come without its month when the range's end carries it: "vom 19. bis 23. Okt".
    private const val RANGE_START = """(\d{1,2})\.(?:\s*(?:(\d{1,2})\.(\d{4}|\d{2})?|($MONTHS)$END\.?))?"""

    private val dateRange = regex(
        """$START(?:vom\s+|von\s+)?$WEEKDAY_PREFIX$RANGE_START\s*(?:bis(?:\s+zum)?|-|–)\s*""" +
            """(?:zum\s+)?$WEEKDAY_PREFIX$FULL_DATE(?!\d)""",
    )

    private val singleDate = regex("""$START(?:am\s+)?$WEEKDAY_PREFIX$FULL_DATE(?!\d)""")

    private val todayWord = regex("""${START}heute$END""")
    private val dayAfterTomorrow = regex("""$START(?:übermorgen|uebermorgen)$END""")
    private val tomorrow = regex("""${START}morgen(?:\s+(?:früh|frueh))?$END""")

    private val nextWeekday = regex(
        """$START(?:am\s+)?(?:nächste|naechste|kommende)[nrms]?\s+($WEEKDAYS)$END""",
    )
    private val weekday = regex("""$START(?:am\s+)?($WEEKDAYS)$END""")

    // Case-sensitive on purpose: "So" and "Do" read as weekdays only as the capitalised abbreviation.
    private val weekdayAbbreviation = Regex("""$START(?:am\s+)?($WEEKDAY_ABBREVIATIONS)$END(\.)?""")

    fun findDate(text: String, today: LocalDate): DateMatch? {
        dateRange.findAll(text).forEach { m ->
            val g = m.groupValues
            val end = dateOf(g[5], g[6], g[7], g[8], g[9], today) ?: return@forEach
            val startMonth = g[2].toIntOrNull() ?: monthNumber(g[4]) ?: end.monthValue
            val startYear = when {
                g[3].isNotEmpty() -> year(g[3])
                startMonth > end.monthValue -> end.year - 1
                else -> end.year
            }
            val start = safeDate(startYear, startMonth, g[1].toInt()) ?: return@forEach
            if (start.isAfter(end)) return@forEach
            return DateMatch(m.range, start, end)
        }
        singleDate.findAll(text).forEach { m ->
            val g = m.groupValues
            val date = dateOf(g[1], g[2], g[3], g[4], g[5], today) ?: return@forEach
            return DateMatch(m.range, date)
        }
        todayWord.find(text)?.let { return DateMatch(it.range, today) }
        dayAfterTomorrow.find(text)?.let { return DateMatch(it.range, today.plusDays(2)) }
        tomorrow.findAll(text).forEach { m ->
            // "am Morgen" and "guten Morgen" are the morning, not tomorrow.
            val before = text.substring(0, m.range.first).trimEnd().substringAfterLast(' ').lowercase()
            if (before == "am" || before == "guten") return@forEach
            return DateMatch(m.range, today.plusDays(1))
        }
        nextWeekday.find(text)?.let { m ->
            val day = weekdayOf(m.groupValues[1]) ?: return@let
            return DateMatch(m.range, QuickAddParser.nextAfter(today, day, strict = true))
        }
        weekday.find(text)?.let { m ->
            val day = weekdayOf(m.groupValues[1]) ?: return@let
            return DateMatch(m.range, QuickAddParser.nextAfter(today, day, strict = false))
        }
        weekdayAbbreviation.findAll(text).forEach { m ->
            if (!readsAsAbbreviation(m, text)) return@forEach
            val day = weekdayOf(m.groupValues[1]) ?: return@forEach
            return DateMatch(m.range, QuickAddParser.nextAfter(today, day, strict = false))
        }
        return null
    }

    /**
     * "So", "Do" and "Mi" are also words, and "Fr." is also "Frau". An abbreviation counts as a day
     * only at the end of the text, before a number, or with its dot and no capitalised name after
     * it ("Fr. Müller").
     */
    private fun readsAsAbbreviation(match: MatchResult, text: String): Boolean {
        val following = text.substring(match.range.last + 1).trimStart()
        val next = following.firstOrNull() ?: return true
        if (next.isDigit()) return true
        return match.groupValues[2].isNotEmpty() && !next.isUpperCase()
    }

    private fun dateOf(
        day: String,
        month: String,
        numericYear: String,
        monthName: String,
        namedYear: String,
        today: LocalDate,
    ): LocalDate? {
        val m = month.toIntOrNull() ?: monthNumber(monthName) ?: return null
        val writtenYear = numericYear.ifEmpty { namedYear }
        if (writtenYear.isNotEmpty()) return safeDate(year(writtenYear), m, day.toInt())
        // Without a year, the next time that day comes round.
        val thisYear = safeDate(today.year, m, day.toInt())
        if (thisYear != null && !thisYear.isBefore(today)) return thisYear
        return safeDate(today.year + 1, m, day.toInt())
    }

    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun monthNumber(name: String): Int? = when (name.lowercase().take(3)) {
        "" -> null
        "jan", "jän", "jae" -> 1
        "feb" -> 2
        "mär", "mae", "mrz" -> 3
        "apr" -> 4
        "mai" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "okt" -> 10
        "nov" -> 11
        "dez" -> 12
        else -> null
    }

    private fun weekdayOf(name: String): DayOfWeek? = when (name.lowercase().take(5)) {
        "sonna" -> DayOfWeek.SATURDAY
        else -> weekdayOfCode(name.lowercase().take(2))
    }

    private fun weekdayOfCode(code: String): DayOfWeek? = when (code) {
        "mo" -> DayOfWeek.MONDAY
        "di" -> DayOfWeek.TUESDAY
        "mi" -> DayOfWeek.WEDNESDAY
        "do" -> DayOfWeek.THURSDAY
        "fr" -> DayOfWeek.FRIDAY
        "sa" -> DayOfWeek.SATURDAY
        "so" -> DayOfWeek.SUNDAY
        else -> null
    }
}
