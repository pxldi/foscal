package app.foscal.notifications

/**
 * Human-readable duration for a reminder's lead time. Keeps the second-largest unit so a
 * 90-minute or 25-hour offset does not collapse to a flat "1h" / "1d" and read as wrong.
 */
internal fun formatLead(minutes: Int): String {
    val total = minutes.coerceAtLeast(0)
    val days = total / 1440
    val hours = (total % 1440) / 60
    val mins = total % 60
    return when {
        days > 0 && hours > 0 -> "${days}d ${hours}h"
        days > 0 -> "${days}d"
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

/**
 * The "how far away is this" prefix on a reminder notification, measured from the moment the
 * notification is actually posted rather than from the reminder's configured offset.
 *
 * The offset is a claim about when the alarm was *meant* to fire. Printing it directly makes the
 * notification restate that claim no matter when it really arrived, so a doze-delayed alarm — or
 * one fired out of band — showed "In 10m" for an event days away instead of exposing the gap.
 */
internal fun leadLabel(startMillis: Long, nowMillis: Long): String? {
    if (startMillis <= 0L) return null
    val minutes = Math.round((startMillis - nowMillis) / 60_000.0).toInt()
    return when {
        minutes > 0 -> "In ${formatLead(minutes)}"
        minutes == 0 -> "Now"
        else -> "${formatLead(-minutes)} ago"
    }
}
