package app.foscal.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

/**
 * Editable view of an iCalendar RRULE covering the subset this app models:
 * frequency, interval, an end condition (COUNT or UNTIL), and — for weekly rules — BYDAY.
 * Exotic tokens (BYMONTHDAY, BYSETPOS, …) are not modelled; the editor preserves the
 * original RRULE verbatim unless the user actually edits recurrence.
 */
data class RecurrenceSpec(
    val frequency: Frequency,
    val interval: Int = 1,
    val count: Int? = null,
    val until: LocalDate? = null,
    val byWeekday: Set<DayOfWeek> = emptySet(),
) {
    val isCustom: Boolean
        get() = interval > 1 || count != null || until != null ||
            (frequency == Frequency.WEEKLY && byWeekday.isNotEmpty())
}

object RecurrenceRules {

    private val untilTimed =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val untilLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * @param zone the event's zone. A timed UNTIL is a UTC instant, and its date is only the date
     *   the user picked once it is read back in that zone: "until 5 January" in New York is stored
     *   as 04:59:59Z on the 6th.
     */
    fun parse(rrule: String?, zone: ZoneId = ZoneOffset.UTC): RecurrenceSpec {
        if (rrule.isNullOrBlank()) return RecurrenceSpec(Frequency.NONE)
        val map = rrule.split(';')
            .filter { '=' in it }
            .associate {
                val (k, v) = it.split('=', limit = 2)
                k.uppercase() to v
            }
        val frequency = when (map["FREQ"]?.uppercase()) {
            "DAILY" -> Frequency.DAILY
            "WEEKLY" -> Frequency.WEEKLY
            "MONTHLY" -> Frequency.MONTHLY
            "YEARLY" -> Frequency.YEARLY
            else -> Frequency.NONE
        }
        return RecurrenceSpec(
            frequency = frequency,
            interval = map["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            count = map["COUNT"]?.toIntOrNull(),
            until = map["UNTIL"]?.let { parseUntilDate(it, zone) },
            byWeekday = map["BYDAY"]
                ?.split(',')
                ?.mapNotNull(::parseDayCode)
                ?.toSet()
                ?: emptySet(),
        )
    }

    fun build(spec: RecurrenceSpec, allDay: Boolean, zone: ZoneId): String? {
        if (spec.frequency == Frequency.NONE) return null
        val parts = mutableListOf("FREQ=${spec.frequency.name}")
        if (spec.interval > 1) parts += "INTERVAL=${spec.interval}"
        // COUNT and UNTIL are mutually exclusive; COUNT wins if both somehow present.
        spec.count?.let { parts += "COUNT=$it" }
            ?: spec.until?.let { parts += "UNTIL=${formatUntil(it, allDay, zone)}" }
        if (spec.frequency == Frequency.WEEKLY && spec.byWeekday.isNotEmpty()) {
            // Order days Monday→Sunday for stable output regardless of set iteration order.
            val ordered = DayOfWeek.values().filter { it in spec.byWeekday }
            parts += "BYDAY=${ordered.joinToString(",") { it.rruleCode() }}"
        }
        return parts.joinToString(";")
    }

    /**
     * Rewrites [rrule] so the series ends strictly before [splitInstant]: COUNT and UNTIL are
     * dropped and a new UNTIL is appended. Every other part stays as written, because the old
     * series keeps its past occurrences only if its rule still generates them. All-day rules use
     * the previous UTC day (DATE); timed rules use one second before [splitInstant] in UTC.
     * Returns null if [rrule] has no valid FREQ. Used to truncate a series for "this and following".
     */
    fun truncateBefore(rrule: String?, splitInstant: Instant, allDay: Boolean): String? {
        val parts = parts(rrule)
        if (!repeats(parts)) return null
        val until = if (allDay) {
            splitInstant.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).format(BASIC_ISO_DATE)
        } else {
            splitInstant.minusSeconds(1).atZone(ZoneOffset.UTC).format(untilTimed)
        }
        val kept = parts.filter { it.first != "COUNT" && it.first != "UNTIL" }
        return format(kept + ("UNTIL" to until))
    }

    /**
     * Rewrites [rrule] for a new series starting at the split of a "this and following" edit.
     * Only a COUNT changes: it is reduced by [occurrencesBeforeSplit] so the following series
     * ends on the same final occurrence as the original. Everything else, UNTIL included, is kept
     * as written. Returns null if [rrule] has no valid FREQ.
     */
    fun rebaseFollowing(rrule: String?, occurrencesBeforeSplit: Int): String? {
        val parts = parts(rrule)
        if (!repeats(parts)) return null
        return format(
            parts.map { (key, value) ->
                val count = value.toIntOrNull()
                if (key == "COUNT" && count != null) {
                    key to (count - occurrencesBeforeSplit).coerceAtLeast(1).toString()
                } else {
                    key to value
                }
            },
        )
    }

    /**
     * The instants in a provider `EXDATE` (or `RDATE`) column.
     *
     * The provider stores the RFC 5545 value without the property name, in one of the forms sync
     * adapters write: UTC date-times (`20261001T080000Z`), local date-times after a zone
     * (`Europe/Vienna;20261001T100000`), or dates (`20261001`, read as UTC midnight like every
     * all-day value). Several properties are joined by newlines. Values that do not parse are
     * skipped, so one odd entry cannot hide the rest.
     */
    fun parseProviderDates(value: String?): List<Instant> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split('\n').flatMap { line ->
            val semicolon = line.indexOf(';')
            val zone = if (semicolon > 0) {
                runCatching { ZoneId.of(line.substring(0, semicolon).trim()) }.getOrNull()
            } else {
                null
            }
            line.substring(semicolon + 1).split(',').mapNotNull { raw ->
                val token = raw.trim()
                runCatching {
                    when {
                        token.length == 8 -> LocalDate.parse(token, BASIC_ISO_DATE)
                            .atStartOfDay(ZoneOffset.UTC).toInstant()
                        token.endsWith("Z", ignoreCase = true) ->
                            LocalDateTime.parse(token.dropLast(1), untilLocal).toInstant(ZoneOffset.UTC)
                        else -> LocalDateTime.parse(token, untilLocal)
                            .atZone(zone ?: ZoneOffset.UTC).toInstant()
                    }
                }.getOrNull()
            }
        }.distinct().sorted()
    }

    /** The rule's parts in their written order, keys upper-cased, values untouched. */
    private fun parts(rrule: String?): List<Pair<String, String>> =
        rrule.orEmpty().split(';')
            .filter { '=' in it }
            .map {
                val (k, v) = it.split('=', limit = 2)
                k.trim().uppercase() to v.trim()
            }

    // Every RFC 5545 frequency, including the ones [Frequency] does not model: a split has to
    // truncate an HOURLY series too, or the old one keeps generating the occurrences it gave away.
    private val rfcFrequencies =
        setOf("SECONDLY", "MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")

    private fun repeats(parts: List<Pair<String, String>>): Boolean =
        parts.any { (k, v) -> k == "FREQ" && v.uppercase() in rfcFrequencies }

    private fun format(parts: List<Pair<String, String>>): String =
        parts.joinToString(";") { (k, v) -> "$k=$v" }

    private fun formatUntil(date: LocalDate, allDay: Boolean, zone: ZoneId): String =
        if (allDay) {
            // All-day rules use a DATE per RFC 5545.
            date.format(BASIC_ISO_DATE)
        } else {
            // Timed rules require UTC: take the end of the local day and convert.
            date.atTime(23, 59, 59).atZone(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(untilTimed)
        }

    private fun parseUntilDate(value: String, zone: ZoneId): LocalDate? {
        val core = value.trim()
        return runCatching {
            when {
                core.endsWith('Z', ignoreCase = true) ->
                    LocalDateTime.parse(core.dropLast(1), untilLocal)
                        .atZone(ZoneOffset.UTC)
                        .withZoneSameInstant(zone)
                        .toLocalDate()
                else -> LocalDate.parse(core.substring(0, 8), BASIC_ISO_DATE)
            }
        }.getOrNull()
    }

    private fun DayOfWeek.rruleCode(): String = when (this) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    private fun parseDayCode(code: String): DayOfWeek? {
        // BYDAY values are 2-letter weekday codes, optionally prefixed with a sign/digit for the
        // nth weekday of the month (e.g. "-1FR" = last Friday). We don't model the prefix, but
        // keep the weekday so the rule round-trips when the user edits other recurrence fields.
        val letters = code.trim().uppercase().takeLastWhile { it.isLetter() }
        return when (letters) {
            "MO" -> DayOfWeek.MONDAY
            "TU" -> DayOfWeek.TUESDAY
            "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY
            "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY
            "SU" -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}
