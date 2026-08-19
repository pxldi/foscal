package app.foscal.core.data

import app.foscal.core.model.AccentColor
import app.foscal.core.model.DayTapAction
import app.foscal.core.model.EventColorStrength
import app.foscal.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

/**
 * Read/write access to the user's preferences. [UserPreferencesRepository] is the production
 * implementation (DataStore-backed); tests supply a simple in-memory fake.
 */
interface Preferences {
    val onboardingCompleted: Flow<Boolean>
    val hiddenCalendarIds: Flow<Set<String>>

    /**
     * Calendars kept out of the month grid while still showing everywhere else.
     *
     * Separate from [hiddenCalendarIds] rather than folded into it because they answer different
     * questions: that one is "do I want this calendar at all", this one is "does it belong in a
     * view where a day is a few millimetres tall". A timeboxed week fills a month grid with
     * blocks that say nothing at that size and crowd out the appointments that do.
     */
    val monthHiddenCalendarIds: Flow<Set<String>>

    /**
     * Events shorter than this many minutes are left out of the month grid. 0 means show all.
     *
     * The blunt companion to [monthHiddenCalendarIds]: that one thins the grid by calendar, this
     * one by weight, without needing a decision per calendar. All-day events are never hidden by
     * it — length is exactly what makes them worth seeing at that size.
     */
    val monthMinimumMinutes: Flow<Int>
    /**
     * Minutes before start to pre-fill on a new event, or null when the user picked "None".
     *
     * Null means *no reminder*, never "not configured yet" — the unset case already resolves to
     * [DEFAULT_REMINDER_MINUTES] here. Callers that treat null as "fall back to 15" silently
     * re-add the alarm the user turned off.
     */
    val defaultReminderMinutes: Flow<Int?>

    /**
     * Per-calendar overrides of [defaultReminderMinutes], keyed by calendar id.
     *
     * A missing key means "no opinion, use the global default"; a key mapped to null means the user
     * explicitly chose "None" for that calendar. See
     * [app.foscal.core.model.CalendarReminderDefaults.resolve], which is the only correct way to
     * combine this with [defaultReminderMinutes].
     */
    val calendarReminderDefaults: Flow<Map<Long, Int?>>
    val accentColor: Flow<AccentColor>
    /** ARGB seed color used when [accentColor] is [AccentColor.CUSTOM]. */
    val accentCustomColor: Flow<Int>
    /**
     * Whether to derive the color scheme from the system wallpaper (Material You) instead of
     * [accentColor]. Off by default: Foscal's own accent is part of its visual identity, and the
     * platform only supplies a dynamic scheme from Android 12 on, so on older releases this has
     * nothing to read and is never offered.
     */
    val dynamicColor: Flow<Boolean>
    val themeMode: Flow<ThemeMode>
    val use24HourClock: Flow<Boolean>

    /**
     * Whether the user has opted into the OpenStreetMap-backed location picker. Off by default —
     * the only feature that uses the network, so it stays disabled until the user turns it on
     * (during onboarding or in Settings).
     */
    val osmMapsEnabled: Flow<Boolean>

    /**
     * The view to open on, or an empty string for "whatever was open last".
     *
     * Stored as the name of a UI-layer view enum rather than a type here, because which views exist
     * is a question about the screen, not about storage. Unknown names resolve to the default, so a
     * view that is renamed or removed degrades to a sensible screen instead of crashing.
     */
    val startView: Flow<String>

    /** The view that was last on screen, so [startView] can mean "carry on where I left off". */
    val lastUsedView: Flow<String>

    /** Which column the week grids and the month grid begin on. */
    val firstDayOfWeek: Flow<DayOfWeek>

    /** How long a new event is when nothing else says otherwise. */
    val defaultEventMinutes: Flow<Int>

    /** Whether the month grid shows ISO week numbers down its left edge. */
    val showWeekNumbers: Flow<Boolean>

    /** What tapping a day header in Week or 3 Days does. */
    val dayTapAction: Flow<DayTapAction>

    /** How saturated an event block's fill is. */
    val eventColorStrength: Flow<EventColorStrength>

    /**
     * Size of the text inside event blocks, as a percentage of the built-in size.
     *
     * A percentage of the app's own sizes rather than a font-scale multiplier: the system's
     * accessibility font scale already applies on top of it, and a grid block is one of the few
     * places where the user may genuinely want *smaller* than the body text so that more of a
     * title fits.
     */
    val eventTextScalePercent: Flow<Int>

    /**
     * Whether a title too long for one line wraps onto the next inside an event block.
     *
     * Off means one line and an ellipsis. In a seven-column week a wrapped title is routinely
     * broken mid-word, which some people would rather not see at all.
     */
    val wrapEventTitles: Flow<Boolean>

    /**
     * Which calendar a new event lands on, or null to use the first visible one.
     *
     * Held as an id rather than a position because calendars come and go with the accounts on the
     * phone, and a position would quietly start meaning a different calendar.
     */
    val defaultCalendarId: Flow<Long?>

    /** Null clears the choice, putting new events back on the first visible calendar. */
    suspend fun setDefaultCalendarId(id: Long?)

    suspend fun setOnboardingCompleted()
    suspend fun setHiddenCalendars(ids: Set<String>)

    suspend fun setMonthHiddenCalendars(ids: Set<String>)

    suspend fun setMonthMinimumMinutes(minutes: Int)
    suspend fun setDefaultReminder(minutes: Int?)

    /** Overrides the default for one calendar; [minutes] of null means "None on this calendar". */
    suspend fun setCalendarReminderDefault(calendarId: Long, minutes: Int?)

    /** Drops [calendarId]'s override so it follows [defaultReminderMinutes] again. */
    suspend fun clearCalendarReminderDefault(calendarId: Long)
    suspend fun setAccentColor(accent: AccentColor)
    suspend fun setAccentCustomColor(color: Int)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUse24HourClock(use24Hour: Boolean)
    suspend fun setOsmMapsEnabled(enabled: Boolean)
    suspend fun setStartView(view: String)
    suspend fun setLastUsedView(view: String)
    suspend fun setFirstDayOfWeek(day: DayOfWeek)
    suspend fun setDefaultEventMinutes(minutes: Int)
    suspend fun setShowWeekNumbers(enabled: Boolean)
    suspend fun setDayTapAction(action: DayTapAction)
    suspend fun setEventColorStrength(strength: EventColorStrength)
    suspend fun setEventTextScalePercent(percent: Int)
    suspend fun setWrapEventTitles(wrap: Boolean)

    companion object {
        /** Reminder offset a brand-new install pre-fills on events. */
        const val DEFAULT_REMINDER_MINUTES = 15

        /** An hour, the length most calendar apps assume and most meetings actually are. */
        const val DEFAULT_EVENT_MINUTES = 60

        /**
         * Monday, not the system locale.
         *
         * Tempting to follow the locale, but the locale is a language setting and this is a habit:
         * plenty of people run an en-US phone and still think of the week as starting on Monday.
         * A fixed default that can be changed to any day is more honest than one that quietly
         * changes when the phone's language does.
         */
        val DEFAULT_FIRST_DAY: DayOfWeek = DayOfWeek.MONDAY

        /** Event text at the size the app was designed around. */
        const val DEFAULT_EVENT_TEXT_SCALE = 100

        /**
         * The sizes worth offering, smallest first.
         *
         * Five stops rather than a continuous slider: the difference a single percent makes to a
         * 10sp label is nothing, and a dial that does nothing invites fiddling with it.
         */
        val EVENT_TEXT_SCALES = listOf(85, 92, 100, 112, 125)
    }
}
