package app.foscal.ui.event

/**
 * Tags common enough in a calendar description to be worth parsing, and rare enough in a note
 * someone typed that finding one is good evidence the text is HTML rather than prose.
 *
 * Google Calendar and Exchange both put HTML in `DESCRIPTION`: a meeting invite arrives as
 * paragraphs of `<br>` with the video-call URL wrapped in an `<a href>`. Rendered as plain text
 * that is a wall of visible markup around the one line anybody wants.
 */
private val HtmlTag = Regex(
    "</?(br|p|div|b|strong|i|em|u|ul|ol|li|a|span|h[1-6])(\\s[^<>]*)?/?>",
    RegexOption.IGNORE_CASE,
)

/**
 * Whether [text] should be treated as HTML.
 *
 * Checked rather than simply always parsing, because an HTML parser is destructive to text that is
 * not HTML: it folds runs of whitespace and drops the newlines a hand-typed note is held together
 * by, so a shopping list one item per line would come back as one paragraph. Only text that
 * actually carries a tag is worth that risk.
 */
fun looksLikeHtml(text: String): Boolean = HtmlTag.containsMatchIn(text)
