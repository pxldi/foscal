package app.foscal.ui.util

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The current locale, read observably.
 *
 * `Locale.getDefault()` is a process-global read: a composable that calls it renders with whatever
 * locale was current at composition and never recomposes when the user changes language, so month
 * and weekday names go stale until the process restarts. `LocalConfiguration` *is* observable, so
 * reading through it makes locale a real input to composition.
 *
 * Non-composable label helpers must take a [Locale] threaded from the call site rather than
 * reaching for the default themselves.
 */
// This is the one sanctioned Locale.getDefault() call in the UI layer: it is only the fallback for
// a LocaleList that came back empty, which a real Configuration never does. Reading the locale
// through LocalConfiguration is what makes every other call site observable, so the lint check the
// fallback trips is precisely the one this function exists to satisfy everywhere else.
@Suppress("NonObservableLocale")
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** A [DateTimeFormatter] for [pattern] in the current locale, rebuilt if either changes. */
@Composable
fun rememberDateFormatter(pattern: String): DateTimeFormatter {
    val locale = currentLocale()
    return remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
}

/**
 * The pattern [locale] itself uses for the fields in [skeleton], in its own order and punctuation:
 * "EEEMMMd" is "EEE, MMM d" in English and "EEE, d. MMM" in German. A pattern written out by hand
 * fixes the English order for every language.
 */
fun localizedPattern(skeleton: String, locale: Locale): String =
    DateFormat.getBestDateTimePattern(locale, skeleton)

/** A [DateTimeFormatter] for [skeleton] laid out the way the current locale writes it. */
@Composable
fun rememberSkeletonFormatter(skeleton: String): DateTimeFormatter {
    val locale = currentLocale()
    return remember(skeleton, locale) {
        DateTimeFormatter.ofPattern(localizedPattern(skeleton, locale), locale)
    }
}
