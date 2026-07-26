package app.foscal.core.model

/**
 * A master event row together with everything the Calendar Provider stores *about* it separately.
 *
 * The provider keeps a per-occurrence change as its own `Events` row pointing back at the series,
 * so a series is only fully described by the master plus those rows. Reading masters alone (the
 * shape export used before) silently drops every single-occurrence edit and deletion.
 */
data class ExportEvent(
    val event: Event,
    /** Occurrences edited individually, in occurrence order. */
    val overrides: List<EventOverride> = emptyList(),
    /** Start instants (epoch millis) of occurrences cancelled from the series, ascending. */
    val cancelledOccurrences: List<Long> = emptyList(),
)

/**
 * One occurrence of a recurring series that was edited on its own.
 *
 * [originalInstanceTime] is the start the occurrence *would* have had — the identity the provider
 * (and RFC 5545's RECURRENCE-ID) uses to say which occurrence is being replaced. [event] carries
 * the replacement values, whose start may well differ.
 */
data class EventOverride(
    val originalInstanceTime: Long,
    /** Whether the series identifies occurrences by date rather than instant (`ORIGINAL_ALL_DAY`). */
    val originalAllDay: Boolean,
    val event: Event,
)
