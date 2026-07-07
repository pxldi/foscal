package app.calendarium.core.model

/**
 * User-selectable accent theme. The design specifies three presets; the whole UI reads
 * `colorScheme.primary`, so switching the accent re-tints the entire app. [key] is the stable
 * string persisted in preferences — do not rename existing values.
 */
enum class AccentColor(val key: String) {
    COBALT("cobalt"),
    VIOLET("violet"),
    FOREST("forest"),
    ;

    companion object {
        val Default = COBALT

        fun fromKey(key: String?): AccentColor =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
