package app.foscal.core.model

/**
 * Per-calendar overrides for the reminder a new event is pre-filled with.
 *
 * One global default does not fit a phone that carries a work calendar (30 minutes, always), a
 * shared family calendar (a day before) and a birthday calendar (no alarm at all). This models the
 * override as a map with a *meaningful absent key*:
 *
 * - key missing  → this calendar has no opinion; use the global default
 * - key → null   → this calendar is explicitly "None"
 * - key → 30     → this calendar pre-fills 30 minutes
 *
 * Collapsing the first two would be the easy mistake, and it makes "no reminders on my birthday
 * calendar" impossible to express.
 *
 * Kept pure, and encoded to a `Set<String>` rather than stored as a map, because Preferences
 * DataStore has no map type — and because a codec with a sentinel in it is exactly the kind of
 * thing that should be tested without a device attached.
 */
object CalendarReminderDefaults {

    /** Stored stand-in for "None". DataStore cannot hold a null, and 0 already means "at start". */
    private const val NONE = -1

    private const val SEPARATOR = ':'

    fun encode(defaults: Map<Long, Int?>): Set<String> =
        defaults.mapTo(mutableSetOf()) { (id, minutes) -> "$id$SEPARATOR${minutes ?: NONE}" }

    /**
     * Rebuilds the map, dropping anything unparseable.
     *
     * Silent over strict: this data outlives the version of the app that wrote it, and one entry
     * mangled by a settings restore should cost that one calendar its override rather than throwing
     * on every read of every preference.
     */
    fun decode(raw: Set<String>): Map<Long, Int?> = buildMap {
        for (entry in raw) {
            val split = entry.indexOf(SEPARATOR)
            if (split <= 0) continue
            val id = entry.substring(0, split).toLongOrNull() ?: continue
            val minutes = entry.substring(split + 1).toIntOrNull() ?: continue
            put(id, minutes.takeIf { it >= 0 })
        }
    }

    /**
     * The offset to pre-fill for an event on [calendarId], falling back to [global].
     *
     * Note the `containsKey` rather than a `?:` on the lookup — `perCalendar[id]` returns null both
     * for "no override" and for an override of "None", and only the first should reach [global].
     */
    fun resolve(calendarId: Long?, perCalendar: Map<Long, Int?>, global: Int?): Int? =
        if (calendarId != null && perCalendar.containsKey(calendarId)) {
            perCalendar[calendarId]
        } else {
            global
        }
}
