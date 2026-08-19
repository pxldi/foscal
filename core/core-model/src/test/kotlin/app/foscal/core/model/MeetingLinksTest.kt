package app.foscal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingLinksTest {

    @Test
    fun `finds a known provider link in the location`() {
        assertEquals(
            "https://meet.google.com/abc-defg-hij",
            MeetingLinks.find("https://meet.google.com/abc-defg-hij", null),
        )
    }

    @Test
    fun `finds a link embedded in a sentence and drops the trailing period`() {
        val notes = "Join us at https://zoom.us/j/123456789. Dial-in below."
        assertEquals("https://zoom.us/j/123456789", MeetingLinks.find(null, notes))
    }

    @Test
    fun `prefers the location over the description`() {
        assertEquals(
            "https://meet.jit.si/standup",
            MeetingLinks.find("https://meet.jit.si/standup", "https://zoom.us/j/1"),
        )
    }

    @Test
    fun `matches subdomains of a known host`() {
        assertEquals(
            "https://acme.zoom.us/j/99",
            MeetingLinks.find("https://acme.zoom.us/j/99"),
        )
    }

    // A lookalike domain is not the provider: `notzoom.us` shares a suffix with `zoom.us` only as
    // a substring, and a substring check would offer to "join" an unrelated page.
    @Test
    fun `does not match a host that merely ends with the provider's name`() {
        assertNull(MeetingLinks.find("https://notzoom.us/j/99"))
    }

    // Nextcloud Talk is self-hosted, so the domain is the user's own and only the path says what
    // the link is.
    @Test
    fun `matches a self-hosted Nextcloud Talk link by path`() {
        assertEquals(
            "https://cloud.example.org/call/xy12ab",
            MeetingLinks.find("https://cloud.example.org/call/xy12ab"),
        )
    }

    @Test
    fun `does not match a path that merely contains a segment name`() {
        assertNull(MeetingLinks.find("https://example.org/recall/notes"))
    }

    @Test
    fun `ignores non-http schemes and plain text`() {
        assertNull(MeetingLinks.find("Room 4B", "geo:0,0?q=Room+4B"))
    }

    @Test
    fun `names the provider for labelling the action`() {
        assertEquals("Google Meet", MeetingLinks.providerName("https://meet.google.com/x"))
        assertEquals("Microsoft Teams", MeetingLinks.providerName("https://teams.microsoft.com/l/x"))
        assertNull(MeetingLinks.providerName("https://cloud.example.org/call/x"))
    }

    @Test
    fun `strips an unbalanced closing bracket but keeps a balanced one`() {
        assertEquals(
            "https://meet.google.com/abc",
            MeetingLinks.find("(see https://meet.google.com/abc)"),
        )
        assertEquals(
            "https://meet.google.com/a(b)",
            MeetingLinks.find("https://meet.google.com/a(b)"),
        )
    }
}
