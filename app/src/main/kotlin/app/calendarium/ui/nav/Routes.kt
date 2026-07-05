package app.calendarium.ui.nav

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val CALENDARS = "calendars"

    /** Edit an existing event by id. */
    const val EVENT_DETAIL = "event/{eventId}"
    fun eventDetail(eventId: Long) = "event/$eventId"

    /**
     * Editor supports both new and edit. `eventId`, `calendarId`, `start`, `end` are all
     * optional; if `eventId` is set it's edit mode, otherwise new with the given pre-fills.
     */
    const val EVENT_EDITOR = "editor?eventId={eventId}&calendarId={calendarId}&start={start}&end={end}"

    fun editorNew(
        calendarId: Long? = null,
        startMillis: Long? = null,
        endMillis: Long? = null,
    ): String {
        val cal = calendarId?.toString() ?: ""
        val start = startMillis?.toString() ?: ""
        val end = endMillis?.toString() ?: ""
        return "editor?eventId=&calendarId=$cal&start=$start&end=$end"
    }

    fun editorEdit(eventId: Long): String = "editor?eventId=$eventId&calendarId=&start=&end="
}
