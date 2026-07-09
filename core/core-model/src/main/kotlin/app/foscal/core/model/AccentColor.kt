package app.foscal.core.model

/**
 * User-selectable accent theme. The design specifies three presets; the whole UI reads
 * `colorScheme.primary`, so switching the accent re-tints the entire app. [key] is the stable
 * string persisted in preferences — do not rename existing values.
 */
enum class AccentColor(val key: String) {
    COBALT("cobalt"),
    VIOLET("violet"),
    FOREST("forest"),

    /** A user-chosen color; its ARGB value is stored separately (see Preferences.accentCustomColor). */
    CUSTOM("custom"),
    ;

    companion object {
        val Default = COBALT

        /** The ARGB seed used for [CUSTOM] until the user picks one — matches the Cobalt preset. */
        const val DEFAULT_CUSTOM_COLOR: Int = 0xFF1A73E8.toInt()

        fun fromKey(key: String?): AccentColor =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
