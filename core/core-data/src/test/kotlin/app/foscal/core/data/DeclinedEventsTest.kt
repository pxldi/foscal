package app.foscal.core.data

import android.provider.CalendarContract.Attendees
import app.foscal.core.model.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class DeclinedEventsTest {

    private fun calendar(id: Long, owner: String?, account: String = "account@example.com") = Calendar(
        id = id,
        displayName = "Cal $id",
        accountName = account,
        accountType = "com.example",
        ownerName = owner,
        color = 0,
        visible = true,
        syncEnabled = true,
    )

    private val work = calendar(1, owner = "Me@Example.com")

    @Test
    fun ownDeclineMatchesAcrossCaseAndMailtoPrefix() {
        val rows = listOf(AttendeeStatusRow(10, "mailto:ME@example.COM", Attendees.ATTENDEE_STATUS_DECLINED))
        assertEquals(setOf(10L), DeclinedEvents.find(rows, mapOf(10L to 1L), listOf(work)))
    }

    @Test
    fun someoneElsesDeclineKeepsTheReminder() {
        val rows = listOf(AttendeeStatusRow(10, "colleague@example.com", Attendees.ATTENDEE_STATUS_DECLINED))
        assertEquals(emptySet<Long>(), DeclinedEvents.find(rows, mapOf(10L to 1L), listOf(work)))
    }

    @Test
    fun acceptedTentativeAndInvitedKeepTheReminder() {
        val rows = listOf(
            AttendeeStatusRow(10, "me@example.com", Attendees.ATTENDEE_STATUS_ACCEPTED),
            AttendeeStatusRow(11, "me@example.com", Attendees.ATTENDEE_STATUS_TENTATIVE),
            AttendeeStatusRow(12, "me@example.com", Attendees.ATTENDEE_STATUS_INVITED),
        )
        val calendarOf = mapOf(10L to 1L, 11L to 1L, 12L to 1L)
        assertEquals(emptySet<Long>(), DeclinedEvents.find(rows, calendarOf, listOf(work)))
    }

    @Test
    fun selfIsJudgedPerCalendar() {
        // The same address declined on a calendar owned by somebody else is not the user's answer.
        val shared = calendar(2, owner = "team@example.com")
        val rows = listOf(AttendeeStatusRow(20, "me@example.com", Attendees.ATTENDEE_STATUS_DECLINED))
        assertEquals(emptySet<Long>(), DeclinedEvents.find(rows, mapOf(20L to 2L), listOf(work, shared)))
    }

    @Test
    fun blankOwnerFallsBackToAccountName() {
        val cal = calendar(3, owner = "", account = "me@example.com")
        val rows = listOf(AttendeeStatusRow(30, "me@example.com", Attendees.ATTENDEE_STATUS_DECLINED))
        assertEquals(setOf(30L), DeclinedEvents.find(rows, mapOf(30L to 3L), listOf(cal)))
    }

    @Test
    fun rowWithoutAddressIsIgnored() {
        val rows = listOf(AttendeeStatusRow(10, null, Attendees.ATTENDEE_STATUS_DECLINED))
        assertEquals(emptySet<Long>(), DeclinedEvents.find(rows, mapOf(10L to 1L), listOf(work)))
    }
}
