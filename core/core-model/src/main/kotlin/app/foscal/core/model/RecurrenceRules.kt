package app.foscal.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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

    fun parse(rrule: String?): RecurrenceSpec {
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
            until = map["UNTIL"]?.let(::parseUntilDate),
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
     * Rewrites [rrule] so the series ends strictly before [splitInstant], preserving
     * FREQ/INTERVAL/BYDAY and replacing any end condition with UNTIL. All-day rules use the
     * previous UTC day (DATE); timed rules use one second before [splitInstant] in UTC, which
     * excludes the split occurrence while keeping every earlier one (recurrences are ≥1 day apart).
     * Returns null if [rrule] is not recurring. Used to truncate a series for "this and following".
     */
    fun truncateBefore(rrule: String?, splitInstant: Instant, allDay: Boolean): String? {
        val spec = parse(rrule)
        if (spec.frequency == Frequency.NONE) return null
        val parts = mutableListOf("FREQ=${spec.frequency.name}")
        if (spec.interval > 1) parts += "INTERVAL=${spec.interval}"
        if (spec.frequency == Frequency.WEEKLY && spec.byWeekday.isNotEmpty()) {
            val ordered = DayOfWeek.values().filter { it in spec.byWeekday }
            parts += "BYDAY=${ordered.joinToString(",") { it.rruleCode() }}"
        }
        val until = if (allDay) {
            splitInstant.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).format(BASIC_ISO_DATE)
        } else {
            splitInstant.minusSeconds(1).atZone(ZoneOffset.UTC).format(untilTimed)
        }
        parts += "UNTIL=$until"
        return parts.joinToString(";")
    }

    /**
     * Rewrites [rrule] for a new series starting at the split of a "this and following" edit:
     * FREQ/INTERVAL/BYDAY/UNTIL are preserved; a COUNT end condition is reduced by
     * [occurrencesBeforeSplit] so the following series ends on the same final occurrence as the
     * original. Returns null if [rrule] is not recurring.
     */
    fun rebaseFollowing(
        rrule: String?,
        occurrencesBeforeSplit: Int,
        allDay: Boolean,
        zone: ZoneId,
    ): String? {
        val spec = parse(rrule)
        if (spec.frequency == Frequency.NONE) return null
        val rebased = if (spec.count != null) {
            spec.copy(count = (spec.count - occurrencesBeforeSplit).coerceAtLeast(1))
        } else {
            spec
        }
        return build(rebased, allDay, zone)
    }

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

    private fun parseUntilDate(value: String): LocalDate? {
        val core = value.trim()
        return runCatching {
            when {
                core.length == 8 -> LocalDate.parse(core, BASIC_ISO_DATE)
                core.endsWith('Z', ignoreCase = true) ->
                    LocalDate.parse(core.substring(0, 8), BASIC_ISO_DATE)
                'T' in core -> LocalDate.parse(core.substring(0, 8), BASIC_ISO_DATE)
                else -> LocalDate.parse(core, BASIC_ISO_DATE)
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
