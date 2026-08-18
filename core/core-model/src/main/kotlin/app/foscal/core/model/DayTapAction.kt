package app.foscal.core.model

/** What tapping a day's header in the Week or 3 Days view does. */
enum class DayTapAction {
    /** Zoom into that day. */
    OPEN_DAY,

    /** Start a new event on it. */
    NEW_EVENT,
    ;

    companion object {
        val Default = OPEN_DAY

        fun fromName(name: String?): DayTapAction =
            entries.firstOrNull { it.name == name } ?: Default
    }
}
