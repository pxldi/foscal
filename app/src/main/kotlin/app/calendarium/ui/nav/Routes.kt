package app.calendarium.ui.nav

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main"
    const val CALENDARS = "calendars"
    const val EVENT_DETAIL = "event/{eventId}"
    fun eventDetail(eventId: Long) = "event/$eventId"
}
