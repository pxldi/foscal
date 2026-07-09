package app.foscal.core.model

/**
 * How the app decides between the light and dark color scheme. [key] is the stable string
 * persisted in preferences — do not rename existing values.
 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        val Default = SYSTEM

        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
