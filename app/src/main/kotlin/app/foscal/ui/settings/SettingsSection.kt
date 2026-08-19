package app.foscal.ui.settings

/**
 * The pages Settings is divided into.
 *
 * Six, and each one named by the thing it holds. There were eight, three of which — Appearance,
 * Calendar style, Calendar — all sounded like the same page, and two of which differed only by an
 * `s`. A name that has to be explained by a line of small print underneath it is not a name, so
 * these carry no summary: anything that cannot be said in a word or two belongs on a page of its
 * own rather than behind a caption.
 */
enum class SettingsSection(val title: String) {
    Appearance("Appearance"),
    Behaviour("Behaviour"),
    Calendars("Calendars"),
    Reminders("Reminders"),
    Transfer("Import & export"),
    About("About"),
    ;

    companion object {
        fun fromName(name: String?): SettingsSection? = entries.firstOrNull { it.name == name }
    }
}
