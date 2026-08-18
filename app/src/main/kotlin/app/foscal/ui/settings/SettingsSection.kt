package app.foscal.ui.settings

/**
 * The pages Settings is divided into.
 *
 * One list of everything had grown to the point where finding a switch meant scrolling past four
 * things you were not looking for. Seven short pages are quicker to search than one long one, and
 * each name says what is behind it so the summary underneath can stay a hint rather than a label.
 */
enum class SettingsSection(val title: String, val summary: String) {
    Appearance("Appearance", "Theme, colour"),
    CalendarView("Calendar", "Which view opens, when the week starts"),
    NewEvents("New events", "Where they go and how long they last"),
    Calendars("Calendars", "What shows, and reminders per calendar"),
    Reminders("Reminders", "When notifications arrive"),
    Transfer("Import & export", "Move events in and out as .ics"),
    About("About", "Version and syncing"),
    ;

    companion object {
        fun fromName(name: String?): SettingsSection? = entries.firstOrNull { it.name == name }
    }
}
