package app.foscal.core.model

data class Calendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val ownerName: String?,
    val color: Int,
    val visible: Boolean,
    val syncEnabled: Boolean,
    /** `CALENDAR_ACCESS_LEVEL`. Defaults to owner, which is what a calendar the app made has. */
    val accessLevel: Int = ACCESS_OWNER,
) {
    val isLocal: Boolean
        get() = accountType == LOCAL_ACCOUNT_TYPE

    /**
     * Whether events can be created, edited, moved or deleted here. An ICSx⁵ subscription or a
     * CalDAV calendar shared read-only sits below contributor, and a write to one is refused or
     * undone by the next sync.
     */
    val isWritable: Boolean
        get() = accessLevel >= ACCESS_CONTRIBUTOR

    companion object {
        const val LOCAL_ACCOUNT_TYPE = "LOCAL"

        /** `CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR`, restated for this JVM module. */
        const val ACCESS_CONTRIBUTOR = 500

        /** `CalendarContract.Calendars.CAL_ACCESS_OWNER`. */
        const val ACCESS_OWNER = 700
    }
}
