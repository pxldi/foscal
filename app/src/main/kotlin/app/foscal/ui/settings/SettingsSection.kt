package app.foscal.ui.settings

import androidx.annotation.StringRes
import app.foscal.R

/**
 * The pages Settings is divided into.
 *
 * Six, and each one named by the thing it holds. There were eight, three of which — Appearance,
 * Calendar style, Calendar — all sounded like the same page, and two of which differed only by an
 * `s`. A name that has to be explained by a line of small print underneath it is not a name, so
 * these carry no summary: anything that cannot be said in a word or two belongs on a page of its
 * own rather than behind a caption.
 */
enum class SettingsSection(@param:StringRes val title: Int) {
    Appearance(R.string.settings_section_appearance),
    Behaviour(R.string.settings_section_behaviour),
    Calendars(R.string.settings_section_calendars),
    Reminders(R.string.settings_section_reminders),
    Transfer(R.string.settings_section_transfer),
    About(R.string.settings_section_about),
    ;

    companion object {
        fun fromName(name: String?): SettingsSection? = entries.firstOrNull { it.name == name }
    }
}
