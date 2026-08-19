package app.foscal.core.model

/**
 * How hard an event block's fill leans on its calendar's colour.
 *
 * A wash toward the surface, not an alpha: alpha would let the grid lines and the today tint show
 * through the block, and the whole point of a block is that it is a solid object on the grid. The
 * text colour is still chosen against whatever fill comes out, so every step stays readable.
 */
enum class EventColorStrength(val key: String, val label: String) {
    VERY_SOFT("very_soft", "Very soft"),
    SOFT("soft", "Soft"),
    BRIGHT("bright", "Bright"),
    FULL("full", "Full"),
    ;

    /** How far toward the surface the fill is pulled, on top of what the theme already does. */
    val wash: Float
        get() = when (this) {
            VERY_SOFT -> 0.72f
            SOFT -> 0.45f
            BRIGHT -> 0.20f
            FULL -> 0f
        }

    companion object {
        /** What the app has always drawn: the colour itself, undiluted. */
        val Default = FULL

        fun fromKey(key: String?): EventColorStrength =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
