package app.foscal.core.model

/** How an invited guest has answered. Mirrors `CalendarContract.Attendees.ATTENDEE_STATUS`. */
enum class AttendeeStatus { INVITED, ACCEPTED, DECLINED, TENTATIVE }

/**
 * One person on an event — a guest, or the organizer.
 *
 * [email] is the identity: RFC 5545 addresses an ATTENDEE by its `mailto:` value and the Calendar
 * Provider matches on `ATTENDEE_EMAIL`, so a row without one cannot be tied back to a person and
 * is dropped on the way in rather than stored as an anonymous entry.
 */
data class Attendee(
    val email: String,
    val name: String? = null,
    val status: AttendeeStatus = AttendeeStatus.INVITED,
    /** Whether this is the event's organizer rather than one of its guests. */
    val isOrganizer: Boolean = false,
    /** Whether attendance is optional (RFC 5545 `ROLE=OPT-PARTICIPANT`). */
    val optional: Boolean = false,
) {
    /** What to show in a list: the display name when the source supplied one, else the address. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: email

    companion object {
        /**
         * Whether [text] is usable as an attendee address.
         *
         * Deliberately far looser than RFC 5322: the only thing the app does with the address is
         * hand it to the provider and to a `mailto:` intent, so rejecting the exotic-but-legal
         * forms a strict check would reject costs the user a guest they can legitimately invite.
         */
        fun isValidEmail(text: String): Boolean {
            val trimmed = text.trim()
            val at = trimmed.indexOf('@')
            return at > 0 &&
                at == trimmed.lastIndexOf('@') &&
                at < trimmed.length - 1 &&
                trimmed.none { it.isWhitespace() } &&
                '.' in trimmed.substring(at + 1)
        }
    }
}
