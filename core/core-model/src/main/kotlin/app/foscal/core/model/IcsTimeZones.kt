package app.foscal.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * One VTIMEZONE: its TZID and the observances it defines.
 *
 * Only the yearly-rule form is modelled (`RRULE:FREQ=YEARLY;BYMONTH=…;BYDAY=…`), which is what
 * Outlook, Exchange and Apple write. A block without one is treated as a fixed offset from the
 * moment it starts.
 */
internal class VTimeZone(
    val tzid: String,
    /** `X-LIC-LOCATION`, which Mozilla and Evolution add naming the IANA zone the block describes. */
    val location: String?,
    val observances: List<Observance>,
) {
    class Observance(
        val daylight: Boolean,
        val start: LocalDateTime,
        val offsetFrom: ZoneOffset,
        val offsetTo: ZoneOffset,
        /** BYMONTH of the yearly rule, or null when the observance does not repeat. */
        val month: Int?,
        /** BYDAY ordinal: 1 for the first, -1 for the last, and so on. */
        val weekOrdinal: Int?,
        val dayOfWeek: DayOfWeek?,
    ) {
        /** The local time this observance begins in [year], or null if it has no rule. */
        fun transitionIn(year: Int): LocalDateTime? {
            val month = month ?: return null
            val ordinal = weekOrdinal ?: return null
            val day = dayOfWeek ?: return null
            val first = LocalDate.of(year, month, 1)
            val date = if (ordinal > 0) {
                first.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, day))
            } else {
                first.with(TemporalAdjusters.lastInMonth(day)).minusWeeks((-ordinal - 1).toLong())
            }
            return date.atTime(start.toLocalTime())
        }
    }

    private val standard: Observance? = observances.lastOrNull { !it.daylight }
    private val daylight: Observance? = observances.lastOrNull { it.daylight }

    /** The offset this definition gives at [instant]. */
    fun offsetAt(instant: Instant): ZoneOffset {
        val std = standard ?: daylight ?: return ZoneOffset.UTC
        val dst = daylight ?: return std.offsetTo
        val year = instant.atOffset(std.offsetTo).year
        val dstStart = dst.transitionIn(year)?.toInstant(dst.offsetFrom) ?: return std.offsetTo
        val stdStart = std.transitionIn(year)?.toInstant(std.offsetFrom) ?: return std.offsetTo
        val inDaylight = if (dstStart < stdStart) {
            instant >= dstStart && instant < stdStart
        } else {
            // Southern hemisphere: daylight time spans the turn of the year.
            instant >= dstStart || instant < stdStart
        }
        return if (inDaylight) dst.offsetTo else std.offsetTo
    }

    val standardOffset: ZoneOffset get() = (standard ?: daylight)?.offsetTo ?: ZoneOffset.UTC
}

/**
 * Turns the TZIDs other apps write into zones the Calendar Provider can store.
 *
 * Tried in order: the id as an IANA name; the IANA name at the end of a prefixed id
 * (`/mozilla.org/20050126_1/Europe/Berlin`); the Windows name table; and last, the file's own
 * VTIMEZONE, matched against every IANA zone. The last step is what rescues Outlook's
 * `Customized Time Zone`, which names nothing and is defined only by its rules. A match is needed
 * rather than just the rules, because the provider stores a zone *name* and expands recurrences
 * with it.
 */
internal class IcsTimeZones(private val definitions: Map<String, VTimeZone>) {

    private val cache = mutableMapOf<Pair<String, Int>, ZoneId?>()

    /** The zone [tzid] means for a value in [year], or null if nothing resolves it. */
    fun resolve(tzid: String, year: Int): ZoneId? {
        val id = tzid.trim().trim('"')
        return cache.getOrPut(id to year) { byName(id) ?: definitions[id]?.let { match(it, year) } }
    }

    private fun byName(id: String): ZoneId? {
        zoneOf(id)?.let { return it }
        if (id.startsWith("/")) {
            val parts = id.split('/').filter { it.isNotEmpty() }
            for (from in parts.indices) zoneOf(parts.drop(from).joinToString("/"))?.let { return it }
        }
        return WINDOWS_ZONES[id]?.let(::zoneOf)
    }

    private fun match(definition: VTimeZone, year: Int): ZoneId? {
        definition.location?.let(::zoneOf)?.let { return it }
        val samples = (0 until 365).map {
            LocalDate.of(year, 1, 1).plusDays(it.toLong()).atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC)
        }
        val expected = samples.map(definition::offsetAt)
        val found = candidates.firstOrNull { zone ->
            val rules = zone.rules
            samples.indices.all { rules.getOffset(samples[it]) == expected[it] }
        }
        // A fixed offset keeps the instants right when no named zone follows these rules.
        return found ?: ZoneId.ofOffset("GMT", definition.standardOffset)
    }

    private companion object {
        /**
         * [id] as a zone, with offsets given a name the provider keeps: `ZoneId.of("Z")` has the
         * id "Z", which `java.util.TimeZone` only reads as GMT by falling back on it.
         */
        fun zoneOf(id: String): ZoneId? = when (val zone = runCatching { ZoneId.of(id) }.getOrNull()) {
            null -> null
            ZoneOffset.UTC -> ZoneId.of("UTC")
            is ZoneOffset -> ZoneId.ofOffset("GMT", zone)
            else -> zone
        }

        /**
         * The zones CLDR picks for each Windows name come first. They are the widely used zone for
         * each set of rules, so a match reads as Europe/Berlin rather than Africa/Ceuta.
         */
        val candidates: List<ZoneId> by lazy {
            val preferred = WINDOWS_ZONES.values.distinct().mapNotNull(::zoneOf)
            val rest = ZoneId.getAvailableZoneIds().sorted()
                .filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
                .mapNotNull(::zoneOf)
            (preferred + rest).distinct()
        }
    }
}
