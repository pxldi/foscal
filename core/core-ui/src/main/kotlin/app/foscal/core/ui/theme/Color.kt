package app.foscal.core.ui.theme

import androidx.compose.ui.graphics.Color

// Cobalt — the default accent (drives today, selection, buttons and the FAB).
// The same blue the launcher icon is built from, so the app a user opens looks like the icon they
// tapped. Every pair below clears 4.5:1 against what it is drawn on.
val FoscalBlue = Color(0xFF4355F4)
val FoscalBlueDark = Color(0xFFAAB2FA)

/** The icon's second blue. Cobalt's secondary, so the palette that made the mark runs on. */
val FoscalPeriwinkle = Color(0xFF8691F7)

/**
 * Tokens for one accent preset, in both light and dark.
 *
 * Two roles, not one. The UI reads `primary` for the things it wants you to look at — today, the
 * FAB, a selection — and `secondaryContainer` for the things that are simply there, like the view
 * switcher and the Edit button. Those used to be handed the primary pair as well, which made every
 * tinted surface in the app the same blue and left the second colour of the palette unused.
 *
 * There is deliberately no tertiary: nothing in the app reads one, and a colour nothing draws is
 * not a palette, it is a comment.
 */
data class AccentTokens(
    val primaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val secondaryLight: Color,
    val secondaryContainerLight: Color,
    val onSecondaryContainerLight: Color,
    val secondaryDark: Color,
    val secondaryContainerDark: Color,
    val onSecondaryContainerDark: Color,
)

val CobaltAccent = AccentTokens(
    primaryLight = FoscalBlue,
    primaryContainerLight = Color(0xFFE8EBFE),
    onPrimaryContainerLight = Color(0xFF19205D),
    primaryDark = FoscalBlueDark,
    onPrimaryDark = Color(0xFF080A1D),
    primaryContainerDark = Color(0xFF202975),
    onPrimaryContainerDark = Color(0xFFE1E4FD),
    // The periwinkle, darkened until it clears 4.5:1 as text on white.
    secondaryLight = Color(0xFF666EBC),
    secondaryContainerLight = Color(0xFFE0E2FD),
    onSecondaryContainerLight = Color(0xFF262945),
    secondaryDark = FoscalPeriwinkle,
    secondaryContainerDark = Color(0xFF2E3154),
    onSecondaryContainerDark = Color(0xFFDDE0FD),
)

val VioletAccent = AccentTokens(
    primaryLight = Color(0xFF6E45E2),
    primaryContainerLight = Color(0xFFEDE7FF),
    onPrimaryContainerLight = Color(0xFF2C1470),
    primaryDark = Color(0xFFC3B0FF),
    onPrimaryDark = Color(0xFF17093B),
    primaryContainerDark = Color(0xFF39236E),
    onPrimaryContainerDark = Color(0xFFE7DEFF),
    secondaryLight = Color(0xFF796D9E),
    secondaryContainerLight = Color(0xFFEFEAFF),
    onSecondaryContainerLight = Color(0xFF373147),
    secondaryDark = Color(0xFFC3B0FF),
    secondaryContainerDark = Color(0xFF423C57),
    onSecondaryContainerDark = Color(0xFFEEE9FF),
)

val ForestAccent = AccentTokens(
    primaryLight = Color(0xFF1E8E5A),
    primaryContainerLight = Color(0xFFD8F2E2),
    onPrimaryContainerLight = Color(0xFF06331E),
    primaryDark = Color(0xFF77D6A2),
    onPrimaryDark = Color(0xFF032013),
    primaryContainerDark = Color(0xFF145033),
    onPrimaryContainerDark = Color(0xFFC9F3D8),
    secondaryLight = Color(0xFF4C7D65),
    secondaryContainerLight = Color(0xFFDEF3E8),
    onSecondaryContainerLight = Color(0xFF243B2F),
    secondaryDark = Color(0xFF7FD1A8),
    secondaryContainerDark = Color(0xFF2B4739),
    onSecondaryContainerDark = Color(0xFFDBF2E7),
)

/**
 * Amber — the day the launcher icon marks, and the day the app marks.
 *
 * A fill only. At 1.5:1 against a white surface it can never be a line, a label or an outline;
 * with dark ink on it, it is 9.8:1, which is better than the blue disc it replaced. [AmberInk] is
 * that ink, and [AmberTextLight] is the darkened form for the places that need amber *as* text.
 */
val FoscalAmber = Color(0xFFFFC94D)
val AmberInk = Color(0xFF19205D)

// Amber where it has to be text rather than a fill: darkened until it clears 4.5:1 on white
// (4.62:1), and left alone in dark where the undarkened colour is already 11.2:1.
val AmberTextLight = Color(0xFF8F712B)
val AmberTextDark = Color(0xFFFFC94D)

val FoscalLightBackground = Color(0xFFF4F6FA)
val FoscalLightSurface = Color(0xFFFFFFFF)
val FoscalLightSurfaceVariant = Color(0xFFF0F3F7)
val FoscalLightOutline = Color(0xFFE2E6EC)

val FoscalDarkBackground = Color(0xFF101318)
val FoscalDarkSurface = Color(0xFF191C21)
val FoscalDarkSurfaceVariant = Color(0xFF22272E)
val FoscalDarkOutline = Color(0xFF343A44)
