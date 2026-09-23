package app.foscal.ui.editor

import androidx.lifecycle.SavedStateHandle
import app.foscal.core.data.FakeCalendarRepository
import app.foscal.core.data.FakePreferences
import app.foscal.core.model.Attendee
import app.foscal.core.model.AttendeeStatus
import app.foscal.core.model.Calendar
import app.foscal.core.model.Event
import app.foscal.ui.feedback.PendingDeletes
import app.foscal.ui.feedback.UserMessages
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val start = Instant.parse("2026-07-09T09:00:00Z")
    private val calendar = Calendar(
        id = 1,
        displayName = "Local",
        accountName = "Foscal",
        accountType = "LOCAL",
        ownerName = null,
        color = 0xFF1976D2.toInt(),
        visible = true,
        syncEnabled = true,
    )
    private val recurring = Event(
        id = 10,
        calendarId = 1,
        title = "Standup",
        location = null,
        description = null,
        start = start,
        end = start.plusSeconds(3_600L),
        allDay = false,
        timezone = "UTC",
        color = 0xFF1976D2.toInt(),
        rrule = "FREQ=WEEKLY",
    )

    private val organizer =
        Attendee("chair@example.org", "Ada Chair", AttendeeStatus.ACCEPTED, isOrganizer = true)
    private val guest = Attendee("bob@example.org", status = AttendeeStatus.INVITED)

    private val caldav = calendar.copy(
        id = 2,
        displayName = "Work",
        accountName = "me@example.org",
        accountType = "bitfire.at.davdroid",
        ownerName = "me@example.org",
    )

    private lateinit var repo: FakeCalendarRepository
    private val messages = UserMessages()
    private lateinit var pendingDeletes: PendingDeletes

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring),
            reminderMinutes = listOf(15),
            attendees = listOf(organizer, guest),
        )
        // On the test dispatcher, so advanceUntilIdle runs the undo window out in virtual time.
        pendingDeletes = PendingDeletes(repo, messages, CoroutineScope(dispatcher))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recurringEditVm(): EventEditorViewModel {
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "10",
                "start" to start.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
    }

    private fun newEventVm(prefs: FakePreferences = FakePreferences()): EventEditorViewModel {
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "",
                "start" to "",
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, prefs, messages, pendingDeletes)
    }

    @Test
    fun `a default reminder of None leaves a new event with no reminders`() = runTest(dispatcher) {
        val vm = newEventVm(FakePreferences(defaultReminder = null))
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), repo.lastCreated?.reminderMinutes)
    }

    @Test
    fun `moving the end before the start drags the start back with it`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        val startDate = vm.state.value.startDate

        vm.updateEndDate(startDate.minusDays(3))

        val state = vm.state.value
        assertEquals(startDate.minusDays(3), state.startDate)
        assertEquals(startDate.minusDays(3), state.endDate)
    }

    @Test
    fun `moving the start moves the end with it`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        val before = vm.state.value
        val length = java.time.Duration.between(
            before.startDate.atTime(before.startTime),
            before.endDate.atTime(before.endTime),
        )

        // The audit's case: 21:00 moved to 20:30 kept its 22:00 end.
        vm.updateStartTime(before.startTime.minusMinutes(30))
        vm.updateStartDate(before.startDate.plusDays(5))

        val state = vm.state.value
        val start = state.startDate.atTime(state.startTime)
        assertEquals(before.startDate.plusDays(5).atTime(before.startTime.minusMinutes(30)), start)
        assertEquals(start.plus(length), state.endDate.atTime(state.endTime))
    }

    @Test
    fun `an end time earlier in the same day never survives as an inverted span`() =
        runTest(dispatcher) {
            val vm = newEventVm()
            advanceUntilIdle()
            val today = vm.state.value.startDate
            vm.updateStartDate(today)
            vm.updateEndDate(today)
            vm.updateStartTime(java.time.LocalTime.of(14, 0))

            vm.updateEndTime(java.time.LocalTime.of(9, 0))

            val state = vm.state.value
            val startAt = state.startDate.atTime(state.startTime)
            val endAt = state.endDate.atTime(state.endTime)
            assertEquals(false, endAt.isBefore(startAt))
        }

    @Test
    fun `saving a new event creates it`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.CREATE, repo.lastOp)
    }

    @Test
    fun `a refused save keeps the editor open with the draft and says so`() = runTest(dispatcher) {
        repo.refuseWrites = true
        val vm = newEventVm()
        advanceUntilIdle()

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.finished)
        assertFalse(state.saving)
        assertEquals("Lunch", state.title)
        assertEquals("Couldn't save the event", messages.messages.first())
    }

    @Test
    fun `a refused this-and-following split is reported like any refused save`() =
        runTest(dispatcher) {
            repo.refuseWrites = true
            val vm = recurringEditVm()
            advanceUntilIdle()

            vm.save()
            vm.resolveScope(RecurrenceScope.THIS_AND_FOLLOWING)
            advanceUntilIdle()

            assertFalse(vm.state.value.finished)
            assertEquals("Couldn't save the event", messages.messages.first())
        }

    @Test
    fun `an event years outside the old scan window still opens for editing`() = runTest(dispatcher) {
        // The lookup used to scan a +-2-year window of instances, so anything beyond it silently
        // fell through to the blank "new event" form and saving created a duplicate.
        val farOff = Instant.parse("2031-03-04T08:00:00Z")
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(id = 42, title = "Passport", start = farOff, end = farOff.plusSeconds(3_600L), rrule = null)),
        )
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "42",
                "start" to farOff.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        val vm = EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(true, state.isEditing)
        assertEquals("Passport", state.title)
        assertEquals(42L, state.eventId)
    }

    @Test
    fun `save on recurring event prompts for scope without writing`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Standup renamed")
        vm.save()
        advanceUntilIdle()

        // Picking a scope is required; nothing should be written yet.
        assertEquals(RecurrenceScopePrompt.SAVE, vm.state.value.scopePrompt)
        assertNull(repo.lastOp)
    }

    @Test
    fun `resolveScope SINGLE routes to updateEventInstance`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.SINGLE)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE_INSTANCE, repo.lastOp)
    }

    @Test
    fun `resolveScope THIS_AND_FOLLOWING routes to updateEventFollowing`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.THIS_AND_FOLLOWING)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE_FOLLOWING, repo.lastOp)
        // Recurrence was not touched in the editor, so the count should be rebased (preserved).
        assertEquals(true, repo.lastRebaseCount)
    }

    @Test
    fun `resolveScope ALL_EVENTS routes to updateEvent`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        vm.resolveScope(RecurrenceScope.ALL_EVENTS)
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
    }

    @Test
    fun `new event loads recent locations newest-first and deduped`() = runTest(dispatcher) {
        fun located(id: Long, location: String?, at: Instant) = recurring.copy(
            id = id,
            location = location,
            start = at,
            end = at.plusSeconds(3_600L),
            rrule = null,
        )
        // Two events share "Room 3B"; the blank one is ignored. Newest start wins for ordering.
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(
                located(1, "Room 3B", start),
                located(2, "Cafeteria", start.plusSeconds(86_400L)),
                located(3, "Room 3B", start.plusSeconds(172_800L)),
                located(4, "  ", start.plusSeconds(259_200L)),
            ),
        )
        val vm = newEventVm()
        advanceUntilIdle()

        assertEquals(listOf("Room 3B", "Cafeteria"), vm.state.value.recentLocations)
    }

    @Test
    fun `editing an event preserves every reminder it already had`() = runTest(dispatcher) {
        // An event synced from CalDAV with three alarms. Renaming it must not drop two of them:
        // updateEvent rewrites the whole reminder set, so the input has to carry all of them.
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(rrule = null)),
            reminderMinutes = listOf(60, 10, 1440),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        assertEquals(listOf(10, 60, 1440), vm.state.value.reminderMinutes)

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
        assertEquals(listOf(10, 60, 1440), repo.lastWritten?.reminderMinutes)
    }

    @Test
    fun `toggleReminder adds removes and clears`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        assertEquals(listOf(15), vm.state.value.reminderMinutes)

        vm.toggleReminder(60)
        assertEquals(listOf(15, 60), vm.state.value.reminderMinutes)

        vm.toggleReminder(15)
        assertEquals(listOf(60), vm.state.value.reminderMinutes)

        vm.toggleReminder(null)
        assertEquals(emptyList<Int>(), vm.state.value.reminderMinutes)

        vm.updateTitle("No alarms")
        vm.save()
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), repo.lastWritten?.reminderMinutes)
    }

    @Test
    fun `editing keeps the event's original timezone instead of the device zone`() =
        runTest(dispatcher) {
            // Authored in New York; edited on a device set to some other zone. Re-anchoring it to
            // the device zone would shift the event for every other client on the calendar.
            repo = FakeCalendarRepository(
                calendars = listOf(calendar),
                events = listOf(recurring.copy(rrule = null, timezone = "America/New_York")),
            )
            val vm = recurringEditVm()
            advanceUntilIdle()

            vm.updateTitle("Renamed")
            vm.save()
            advanceUntilIdle()

            assertEquals("America/New_York", repo.lastWritten?.timezone)
        }

    @Test
    fun `an unparseable stored timezone falls back to the device zone`() = runTest(dispatcher) {
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(recurring.copy(rrule = null, timezone = "Not/AZone")),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals(ZoneId.systemDefault().id, repo.lastWritten?.timezone)
    }

    @Test
    fun `an all-day event is stored in UTC regardless of its original zone`() = runTest(dispatcher) {
        repo = FakeCalendarRepository(
            calendars = listOf(calendar),
            events = listOf(
                recurring.copy(rrule = null, allDay = true, timezone = "America/New_York"),
            ),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.updateTitle("Renamed")
        vm.save()
        advanceUntilIdle()

        assertEquals("UTC", repo.lastWritten?.timezone)
    }

    @Test
    fun `a new event is authored in the device zone`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()

        vm.updateTitle("Lunch")
        vm.save()
        advanceUntilIdle()

        assertEquals(ZoneId.systemDefault().id, repo.lastWritten?.timezone)
    }

    @Test
    fun `delete SINGLE vs FOLLOWING vs ALL route correctly`() = runTest(dispatcher) {
        // SINGLE
        val a = recurringEditVm()
        advanceUntilIdle()
        a.delete()
        advanceUntilIdle()
        a.confirmDelete(RecurrenceScope.SINGLE)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_INSTANCE, repo.lastOp)

        // FOLLOWING
        repo.reset()
        val b = recurringEditVm()
        advanceUntilIdle()
        b.delete()
        advanceUntilIdle()
        b.confirmDelete(RecurrenceScope.THIS_AND_FOLLOWING)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE_FOLLOWING, repo.lastOp)

        // ALL
        repo.reset()
        val c = recurringEditVm()
        advanceUntilIdle()
        c.delete()
        advanceUntilIdle()
        c.confirmDelete(RecurrenceScope.ALL_EVENTS)
        advanceUntilIdle()
        assertEquals(FakeCalendarRepository.Op.DELETE, repo.lastOp)
        assertTrue(c.state.value.deleted)
    }

    @Test
    fun `read-only calendars are not offered and their events cannot be saved`() = runTest(dispatcher) {
        val subscription = calendar.copy(id = 3, displayName = "Holidays", accessLevel = 200)
        repo = FakeCalendarRepository(
            calendars = listOf(calendar, subscription),
            events = listOf(recurring.copy(calendarId = 3)),
            reminderMinutes = listOf(15),
        )
        val vm = recurringEditVm()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(listOf(1L), state.availableCalendars.map { it.id })
        assertTrue(state.calendarReadOnly)
        assertFalse(state.canSave)

        val fresh = newEventVm()
        advanceUntilIdle()
        assertEquals(listOf(1L), fresh.state.value.availableCalendars.map { it.id })
        assertFalse(fresh.state.value.calendarReadOnly)
    }

    @Test
    fun `delete asks before it deletes`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.delete()
        assertTrue(vm.state.value.deletePrompt)
        assertFalse(vm.state.value.finished)
        assertTrue(pendingDeletes.pending.value.isEmpty())

        vm.dismissDeletePrompt()
        assertFalse(vm.state.value.deletePrompt)
        // A confirm with no prompt showing is a stale tap and does nothing.
        vm.confirmDelete(RecurrenceScope.ALL_EVENTS)
        assertFalse(vm.state.value.finished)
        assertTrue(pendingDeletes.pending.value.isEmpty())
    }

    @Test
    fun `an untouched editor is not dirty and an edit makes it so`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)

        vm.toggleCustomRecurrence()
        assertFalse(vm.state.value.dirty)

        vm.updateTitle("Standup!")
        assertTrue(vm.state.value.dirty)

        vm.updateTitle("Standup")
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `a draft survives the process being killed`() = runTest(dispatcher) {
        val handle = SavedStateHandle(
            mapOf("eventId" to "10", "start" to start.toEpochMilli().toString()),
        )
        val first = EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
        advanceUntilIdle()
        first.updateTitle("Retro")
        first.updateLocation("Room 4")
        first.updateStartTime(first.state.value.startTime.plusHours(2))
        first.toggleReminder(60)
        first.updateGuestDraft("carol@example.org")
        val typed = first.state.value

        // A new view model over the same saved state is what Android builds after a process death.
        val second = EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
        advanceUntilIdle()
        val restored = second.state.value

        assertEquals("Retro", restored.title)
        assertEquals("Room 4", restored.location)
        assertEquals(typed.startTime, restored.startTime)
        assertEquals(typed.endTime, restored.endTime)
        assertEquals(typed.reminderMinutes, restored.reminderMinutes)
        assertEquals(typed.attendees, restored.attendees)
        assertEquals("carol@example.org", restored.guestDraft)
        assertEquals(10L, restored.eventId)
        assertTrue(restored.dirty)
    }

    @Test
    fun `a delete waits out the undo window and an undo cancels it`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        vm.delete()
        vm.confirmDelete(RecurrenceScope.ALL_EVENTS)

        // The editor closes straight away, but nothing has reached the provider yet.
        assertTrue(vm.state.value.finished)
        assertNull(repo.lastOp)

        pendingDeletes.undo(pendingDeletes.pending.value.single().key)
        advanceUntilIdle()
        assertNull(repo.lastOp)
    }

    @Test
    fun `a one-day all-day event survives an edit without growing a day`() = runTest(dispatcher) {
        // The provider stores an all-day END as exclusive UTC midnight of the day after the last
        // covered day, so this one-day event on the 19th is stored as 19th -> 20th.
        val day = LocalDate.of(2026, 8, 19)
        val allDay = Event(
            id = 20,
            calendarId = 1,
            title = "Holiday",
            location = null,
            description = null,
            start = day.atStartOfDay(ZoneOffset.UTC).toInstant(),
            end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
            allDay = true,
            timezone = "UTC",
            color = 0xFF1976D2.toInt(),
        )
        repo = FakeCalendarRepository(calendars = listOf(calendar), events = listOf(allDay))
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to "20",
                "start" to allDay.start.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        val vm = EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
        advanceUntilIdle()

        // The editor shows the last covered day, not the exclusive end.
        assertEquals(day, vm.state.value.startDate)
        assertEquals(day, vm.state.value.endDate)

        // And saving it straight back writes the same instants it read, rather than pushing the
        // end out by a day on every visit to the editor.
        vm.save()
        advanceUntilIdle()

        assertEquals(allDay.start, repo.lastWritten?.start)
        assertEquals(allDay.end, repo.lastWritten?.end)
    }

    @Test
    fun `a new event writes an empty guest list rather than leaving it untouched`() =
        runTest(dispatcher) {
            val vm = newEventVm()
            advanceUntilIdle()
            vm.updateTitle("Solo")
            vm.save()
            advanceUntilIdle()

            assertEquals(emptyList<Attendee>(), repo.lastCreated?.attendees)
        }

    // The editor loads the whole guest list precisely so that saving an unrelated edit writes it
    // back intact: [EventInput.attendees] replaces the list wholesale, so anything not carried
    // through here is a guest silently un-invited.
    @Test
    fun `an unrelated edit preserves guests the editor did not add`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()
        assertEquals(listOf(organizer, guest), vm.state.value.attendees)

        vm.updateTitle("Standup (moved)")
        vm.save()
        advanceUntilIdle()
        vm.resolveScope(RecurrenceScope.ALL_EVENTS)
        advanceUntilIdle()

        assertEquals(listOf(organizer, guest), repo.lastWritten?.attendees)
    }

    @Test
    fun `adding a guest requires a well-formed address the list does not already have`() =
        runTest(dispatcher) {
            val vm = recurringEditVm()
            advanceUntilIdle()

            vm.updateGuestDraft("not-an-email")
            assertFalse(vm.state.value.canAddGuest)
            vm.addGuest()
            assertEquals(2, vm.state.value.attendees.size)

            vm.updateGuestDraft("BOB@example.org")
            assertFalse(vm.state.value.canAddGuest)

            vm.updateGuestDraft(" carol@example.org ")
            assertTrue(vm.state.value.canAddGuest)
            vm.addGuest()
            assertEquals(
                listOf("chair@example.org", "bob@example.org", "carol@example.org"),
                vm.state.value.attendees.map { it.email },
            )
            assertEquals("", vm.state.value.guestDraft)
        }

    // Tapping Save straight from the guest field never fires the field's own Done action, so an
    // address typed there would otherwise be dropped on the floor.
    @Test
    fun `saving commits an address still sitting in the guest field`() = runTest(dispatcher) {
        val vm = newEventVm()
        advanceUntilIdle()
        vm.updateTitle("Lunch")
        vm.updateGuestDraft("dana@example.org")
        vm.save()
        advanceUntilIdle()

        assertEquals(listOf("dana@example.org"), repo.lastCreated?.attendees?.map { it.email })
    }

    // The organizer is the event's owner in both RFC 5545 and the provider; removing that row
    // un-invites nobody, it only loses which address the invitation came from.
    @Test
    fun `the organizer cannot be removed but a guest can`() = runTest(dispatcher) {
        val vm = recurringEditVm()
        advanceUntilIdle()

        vm.removeGuest(organizer.email)
        assertEquals(listOf(organizer, guest), vm.state.value.attendees)

        vm.removeGuest("BOB@example.org")
        assertEquals(listOf(organizer), vm.state.value.attendees)
    }

    /** An editor over [event], on the calendars and with the guest list given. */
    private fun editorFor(
        event: Event,
        calendars: List<Calendar>,
        attendees: List<Attendee>,
    ): EventEditorViewModel {
        repo = FakeCalendarRepository(
            calendars = calendars,
            events = listOf(event),
            reminderMinutes = emptyList(),
            attendees = attendees,
        )
        val handle = SavedStateHandle(
            mapOf(
                "eventId" to event.id.toString(),
                "start" to event.start.toEpochMilli().toString(),
                "calendarId" to "",
                "end" to "",
            ),
        )
        return EventEditorViewModel(handle, repo, FakePreferences(), messages, pendingDeletes)
    }

    // Rewriting the ATTENDEE rows of an event somebody else organized is a scheduling message, not
    // an edit, and what a CalDAV server does with one varies. The editor does not offer it.
    @Test
    fun `guests are not editable on an event organized by someone else`() = runTest(dispatcher) {
        val vm = editorFor(
            event = recurring.copy(calendarId = caldav.id),
            calendars = listOf(caldav),
            attendees = listOf(
                Attendee("boss@example.org", "The Boss", isOrganizer = true),
                Attendee("me@example.org", status = AttendeeStatus.ACCEPTED),
            ),
        )
        advanceUntilIdle()

        assertFalse(vm.state.value.canEditGuests)
        assertFalse(vm.state.value.canAddGuest)
    }

    // Writing the list back even unchanged re-sends it to the server, so the save must carry null
    // — the repository's "leave the guests alone" case — rather than the loaded list.
    @Test
    fun `saving an event we did not organize leaves its guest list untouched`() =
        runTest(dispatcher) {
            val vm = editorFor(
                event = recurring.copy(calendarId = caldav.id, rrule = null),
                calendars = listOf(caldav),
                attendees = listOf(Attendee("boss@example.org", isOrganizer = true)),
            )
            advanceUntilIdle()

            vm.updateTitle("Standup (renamed)")
            vm.save()
            advanceUntilIdle()

            assertEquals(FakeCalendarRepository.Op.UPDATE, repo.lastOp)
            assertNull(repo.lastWritten?.attendees)
        }

    @Test
    fun `the gate holds against a mutation call that bypasses the disabled controls`() =
        runTest(dispatcher) {
            val locked = listOf(
                Attendee("boss@example.org", isOrganizer = true),
                Attendee("me@example.org"),
            )
            val vm = editorFor(
                event = recurring.copy(calendarId = caldav.id),
                calendars = listOf(caldav),
                attendees = locked,
            )
            advanceUntilIdle()

            vm.updateGuestDraft("intruder@example.org")
            vm.addGuest()
            vm.removeGuest("me@example.org")

            assertEquals(locked, vm.state.value.attendees)
        }

    @Test
    fun `guests stay editable when the organizer is the calendar's owner`() = runTest(dispatcher) {
        val vm = editorFor(
            event = recurring.copy(calendarId = caldav.id),
            calendars = listOf(caldav),
            attendees = listOf(Attendee("ME@example.org", isOrganizer = true)),
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.canEditGuests)
    }

    // A plain CalDAV event created by a non-scheduling client carries no ORGANIZER at all. There is
    // nobody whose event it is instead, so locking the field would strand the common case.
    @Test
    fun `guests stay editable on a synced event that names no organizer`() = runTest(dispatcher) {
        val vm = editorFor(
            event = recurring.copy(calendarId = caldav.id),
            calendars = listOf(caldav),
            attendees = emptyList(),
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.canEditGuests)
    }

    // No server, no scheduling to get wrong — a local calendar is always the user's own.
    @Test
    fun `guests stay editable on a local calendar whatever the organizer says`() =
        runTest(dispatcher) {
            val vm = editorFor(
                event = recurring,
                calendars = listOf(calendar),
                attendees = listOf(Attendee("someone@else.example", isOrganizer = true)),
            )
            advanceUntilIdle()

            assertTrue(vm.state.value.canEditGuests)
        }
}
