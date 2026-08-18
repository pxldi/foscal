package app.foscal

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouteTest {

    @Test
    fun `viewing a time lands on that day`() {
        val route = routeFor(
            RouteRequest(action = "android.intent.action.VIEW", path = "time", id = 1_755_000_000_000L),
        )
        assertEquals(IntentRoute.Day(1_755_000_000_000L), route)
    }

    @Test
    fun `viewing the time collection means today, which needs no navigation`() {
        val route = routeFor(RouteRequest(action = "android.intent.action.VIEW", path = "time"))
        assertEquals(IntentRoute.None, route)
    }

    @Test
    fun `viewing an event opens it`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.VIEW",
                path = "events",
                id = 42L,
                beginMillis = 1_700_000_000_000L,
            ),
        )
        assertEquals(IntentRoute.Event(42L, 1_700_000_000_000L), route)
    }

    @Test
    fun `an occurrence with no begin time still opens the event`() {
        val route = routeFor(
            RouteRequest(action = "android.intent.action.VIEW", path = "events", id = 42L),
        )
        assertEquals(IntentRoute.Event(42L, 0L), route)
    }

    @Test
    fun `inserting carries the sender's fields into the editor`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.INSERT",
                path = "events",
                beginMillis = 1L,
                endMillis = 2L,
                title = "Standup",
                location = "Room 3",
                description = "Notes",
                allDay = true,
            ),
        )
        assertEquals(
            IntentRoute.NewEvent(
                title = "Standup",
                location = "Room 3",
                description = "Notes",
                startMillis = 1L,
                endMillis = 2L,
                allDay = true,
            ),
            route,
        )
    }

    @Test
    fun `editing with an id edits that event`() {
        val route = routeFor(
            RouteRequest(action = "android.intent.action.EDIT", path = "events", id = 7L, beginMillis = 9L),
        )
        assertEquals(IntentRoute.EditEvent(7L, 9L), route)
    }

    // Senders learnt this from Google Calendar, which has always taken EDIT on the collection to
    // mean "new event". Treating it as a no-op would drop the event on the floor.
    @Test
    fun `editing with no id makes a new event`() {
        val route = routeFor(
            RouteRequest(action = "android.intent.action.EDIT", path = "events", title = "Lunch"),
        )
        assertEquals(IntentRoute.NewEvent(title = "Lunch"), route)
    }

    @Test
    fun `a URI on somebody else's authority is ignored`() {
        val route = routeFor(RouteRequest(action = "android.intent.action.VIEW", path = null, id = 42L))
        assertEquals(IntentRoute.None, route)
    }

    @Test
    fun `an ics file offers to import`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.VIEW",
                uri = "content://downloads/1234",
                mimeType = "text/calendar",
            ),
        )
        assertEquals(IntentRoute.ImportIcs("content://downloads/1234"), route)
    }

    // The common case in the wild: a provider that could not name the type, so only the file name
    // says what this is.
    @Test
    fun `an ics file with no usable type is recognised by its name`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.VIEW",
                uri = "file:///storage/emulated/0/Download/invite.ICS",
                mimeType = "application/octet-stream",
            ),
        )
        assertEquals(IntentRoute.ImportIcs("file:///storage/emulated/0/Download/invite.ICS"), route)
    }

    @Test
    fun `a shared ics file offers to import`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.SEND",
                uri = "content://mail/attachment/9",
                mimeType = "text/calendar",
            ),
        )
        assertEquals(IntentRoute.ImportIcs("content://mail/attachment/9"), route)
    }

    @Test
    fun `a file that is neither named nor typed as a calendar is left alone`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.VIEW",
                uri = "content://downloads/1234",
                mimeType = "application/octet-stream",
            ),
        )
        assertEquals(IntentRoute.None, route)
    }

    // The file rules run first, so this checks they do not swallow a calendar URI on the way past.
    @Test
    fun `a calendar URI is still read as a calendar URI`() {
        val route = routeFor(
            RouteRequest(
                action = "android.intent.action.VIEW",
                uri = "content://com.android.calendar/events/42",
                path = "events",
                id = 42L,
            ),
        )
        assertEquals(IntentRoute.Event(42L, 0L), route)
    }

    @Test
    fun `a plain launch does nothing`() {
        assertEquals(IntentRoute.None, routeFor(RouteRequest(action = "android.intent.action.MAIN")))
        assertEquals(IntentRoute.None, routeFor(RouteRequest(action = null)))
    }
}
