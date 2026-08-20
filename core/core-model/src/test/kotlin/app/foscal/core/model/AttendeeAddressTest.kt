package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one rule both sides of a reply use to decide "same person".
 *
 * Pinned because the two sides used to disagree: the screen compared case-insensitively and the
 * write handed `attendeeEmail = ?` to SQLite, which does not — so an invitation could offer
 * buttons that then matched no row.
 */
class AttendeeAddressTest {

    @Test
    fun `case is not part of an address`() {
        assertEquals(
            Attendee.normalizeAddress("Me@Example.COM"),
            Attendee.normalizeAddress("me@example.com"),
        )
    }

    @Test
    fun `a mailto prefix is not part of an address`() {
        assertEquals("me@example.com", Attendee.normalizeAddress("mailto:me@example.com"))
        assertEquals("me@example.com", Attendee.normalizeAddress("MAILTO:Me@Example.com"))
    }

    @Test
    fun `surrounding space is not part of an address`() {
        assertEquals("me@example.com", Attendee.normalizeAddress("  me@example.com "))
        assertEquals("me@example.com", Attendee.normalizeAddress("mailto: me@example.com"))
    }

    @Test
    fun `an address that is already normal is left alone`() {
        assertEquals("me@example.com", Attendee.normalizeAddress("me@example.com"))
    }
}
