package app.foscal.core.ui.theme

/**
 * How long anything is allowed to move.
 *
 * These are deliberately short. Every transition in this app follows something the user did — a
 * tap, a swipe — and until it finishes the screen is showing a state that is on its way out. A
 * 300ms slide is a third of a second between "I swiped" and "I can read the month I asked for",
 * repeated on every navigation. The animation's job here is only to say which way the content
 * went; it is not the point of the interaction, so it gets out of the way quickly.
 *
 * Three durations and nothing else. There were also five spring/tween builders and a pair of alpha
 * constants here that nothing in the app ever called — a design system nobody uses is worse than
 * none, because the next person assumes it is load-bearing and matches it.
 */
object Motion {
    const val DurationShort = 90
    const val DurationMedium = 160
    const val DurationLong = 260
}
