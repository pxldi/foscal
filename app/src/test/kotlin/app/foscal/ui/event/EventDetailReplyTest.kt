package app.foscal.ui.event

import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules deciding whether an event is an invitation the user can answer. */
class EventDetailReplyTest {

    private fun calendar(owner: String?) = Calendar(
        id = 1,
        displayName = "Work",
        accountName = "me@example.com",
        accountType = "com.example",
        ownerName = owner,
        color = 0,
        visible = true,
        syncEnabled = true,
    )

    private val me = Attendee("me@example.com", status = AttendeeStatus.INVITED)
    private val boss = Attendee("boss@example.com", isOrganizer = true)

    @Test
    fun `an invitation naming the user can be answered`() {
        val state = EventDetailUiState(
            calendar = calendar("me@example.com"),
            attendees = listOf(boss, me),
        )
        assertTrue(state.canReply)
        assertEquals(AttendeeStatus.INVITED, state.selfAttendee?.status)
    }

    // Providers are inconsistent about the case of an address, and RFC 5321 makes the domain
    // case-insensitive regardless.
    @Test
    fun `the address match ignores case`() {
        val state = EventDetailUiState(
            calendar = calendar("ME@Example.COM"),
            attendees = listOf(boss, me),
        )
        assertTrue(state.canReply)
    }

    @Test
    fun `an event the user organized is not an invitation to themselves`() {
        val state = EventDetailUiState(
            calendar = calendar("me@example.com"),
            attendees = listOf(me.copy(isOrganizer = true), boss.copy(isOrganizer = false)),
        )
        assertFalse(state.canReply)
    }

    @Test
    fun `a guest list that does not name the user offers nothing to answer`() {
        val state = EventDetailUiState(
            calendar = calendar("someone@else.example"),
            attendees = listOf(boss, me),
        )
        assertFalse(state.canReply)
        assertNull(state.selfAttendee)
    }

    @Test
    fun `an event with no guests offers nothing to answer`() {
        val state = EventDetailUiState(calendar = calendar("me@example.com"))
        assertFalse(state.canReply)
    }

    @Test
    fun `a calendar with no owner address offers nothing to answer`() {
        val state = EventDetailUiState(calendar = calendar(null), attendees = listOf(boss, me))
        assertFalse(state.canReply)
    }
}
