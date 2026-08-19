package app.foscal.core.model

/**
 * Finds the video-call link an event carries, so the detail screen can offer a "Join" action
 * instead of making the user hunt through the notes for a URL.
 *
 * Nothing here goes near the network — the match is a pure string scan and the result is handed to
 * the user's browser only when they tap it.
 */
object MeetingLinks {

    /**
     * The conferencing link in [fields], searched in the order given, or null if there is none.
     *
     * Callers pass location before description: a meeting whose location *is* the link means it
     * literally, while a description routinely also quotes a dial-in page or a recording link
     * further down, and the first URL in the body is the one the invite leads with.
     */
    fun find(vararg fields: String?): String? =
        fields.asSequence()
            .filterNotNull()
            .mapNotNull { field -> urlsIn(field).firstOrNull(::isMeetingUrl) }
            .firstOrNull()

    /** Provider name for the matched [url], for labelling the action ("Join Google Meet"). */
    fun providerName(url: String): String? {
        val host = hostOf(url) ?: return null
        return PROVIDER_NAMES.entries.firstOrNull { (suffix, _) -> host.matchesHost(suffix) }?.value
    }

    private fun isMeetingUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (MEETING_HOSTS.any { host.matchesHost(it) }) return true
        // Nextcloud Talk and BigBlueButton are self-hosted, so the host is the user's own domain
        // and only the path identifies them. Anchoring on a path segment rather than a substring
        // keeps an ordinary page like /recall/notes from matching.
        val path = url.substringAfter(host, "").substringBefore('?').substringBefore('#')
        return path.split('/').any { it in SELF_HOSTED_PATH_SEGMENTS }
    }

    /** Whether this host is [suffix] itself or a subdomain of it (never `notzoom.us`). */
    private fun String.matchesHost(suffix: String): Boolean =
        this == suffix || endsWith(".$suffix")

    private fun hostOf(url: String): String? {
        val scheme = url.substringBefore("://", "")
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        val authority = url.substringAfter("://").substringBefore('/')
            .substringAfter('@') // userinfo, if any
            .substringBefore(':') // port
        return authority.lowercase().takeIf { it.isNotEmpty() }
    }

    /**
     * Every http(s) URL in [text].
     *
     * Trailing punctuation is trimmed because a link is usually written inside a sentence
     * ("join at https://meet.example.com/abc.") and the period is not part of it. Brackets are
     * balanced rather than blindly stripped so a Matrix-style `(https://…/a(b))` survives.
     */
    private fun urlsIn(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("http", i, ignoreCase = true)
            if (start < 0) break
            val end = text.indexOfFirst(start) { it.isWhitespace() || it == '<' || it == '"' }
            val raw = text.substring(start, end)
            if ("://" in raw) out += raw.trimEnd('.', ',', ';', ':', '!', '?').trimUnbalanced()
            i = end + 1
        }
        return out
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    private fun String.trimUnbalanced(): String {
        var result = this
        while (result.endsWith(')') && result.count { it == '(' } < result.count { it == ')' }) {
            result = result.dropLast(1)
        }
        while (result.endsWith('>')) result = result.dropLast(1)
        return result
    }

    private val PROVIDER_NAMES = linkedMapOf(
        "meet.google.com" to "Google Meet",
        "zoom.us" to "Zoom",
        "zoom.com" to "Zoom",
        "teams.microsoft.com" to "Microsoft Teams",
        "teams.live.com" to "Microsoft Teams",
        "webex.com" to "Webex",
        "jit.si" to "Jitsi",
        "whereby.com" to "Whereby",
        "gotomeeting.com" to "GoTo Meeting",
        "gotomeet.me" to "GoTo Meeting",
        "bluejeans.com" to "BlueJeans",
        "8x8.vc" to "8x8",
        "chime.aws" to "Amazon Chime",
    )

    private val MEETING_HOSTS = PROVIDER_NAMES.keys

    /** Path segments that identify a self-hosted conference on an otherwise unknown domain. */
    private val SELF_HOSTED_PATH_SEGMENTS = setOf("call", "bigbluebutton", "jitsi")
}
