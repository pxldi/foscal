package app.foscal.ui.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {

    @Test
    fun `plain notes are left alone`() {
        assertFalse(looksLikeHtml("Bring a jacket"))
        assertFalse(looksLikeHtml("Milk\nEggs\nBread"))
    }

    @Test
    fun `comparisons and arrows are not markup`() {
        // The reason the check is a tag pattern and not a search for angle brackets.
        assertFalse(looksLikeHtml("Budget < 500 EUR"))
        assertFalse(looksLikeHtml("Q3 -> Q4 handover"))
        assertFalse(looksLikeHtml("mail me <leo@example.com>"))
    }

    @Test
    fun `line breaks and formatting are markup`() {
        assertTrue(looksLikeHtml("Agenda:<br>1. Budget<br>2. Hiring"))
        assertTrue(looksLikeHtml("Please read <b>before</b> the call"))
        assertTrue(looksLikeHtml("<p>Standup</p>"))
    }

    @Test
    fun `a meeting link is markup`() {
        assertTrue(
            looksLikeHtml("Join at <a href=\"https://meet.example.com/abc-defg\">this link</a>"),
        )
    }

    @Test
    fun `case and attributes do not matter`() {
        assertTrue(looksLikeHtml("Line one<BR />Line two"))
        assertTrue(looksLikeHtml("<span style=\"color:red\">Late</span>"))
    }
}
