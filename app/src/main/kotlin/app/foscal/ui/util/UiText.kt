package app.foscal.ui.util

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

/**
 * Text decided outside a composable but read inside one.
 *
 * A view model, a singleton or a pure helper has no [Resources] of its own, and resolving a string
 * when it is *posted* freezes it in whatever language was current then: a snackbar queued before a
 * language change would come up in the old one. So the text travels as a resource id plus its
 * arguments and is resolved where it is shown. Data classes, so a unit test can compare what was
 * posted without a device: `R` ids are plain ints in a JVM test.
 *
 * An argument that is itself a [UiText] is resolved first, which is how a message can carry a
 * label built elsewhere ("Reminds %s before" with a reminder label inside it).
 */
sealed interface UiText {

    fun resolve(resources: Resources): String

    /** Text that is already in its final form: a title, a calendar name, a server's message. */
    data class Raw(val text: String) : UiText {
        override fun resolve(resources: Resources): String = text
    }

    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(resources: Resources): String =
            if (args.isEmpty()) {
                resources.getString(id)
            } else {
                resources.getString(id, *resolveArgs(resources, args))
            }
    }

    /**
     * A quantity string. [count] selects the form; [args] fill it, and default to [count] alone,
     * which is what a `%d` plural wants.
     */
    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText {
        override fun resolve(resources: Resources): String =
            resources.getQuantityString(id, count, *resolveArgs(resources, args))
    }

    /** Several pieces shown as one, joined by [separator] after each is resolved. */
    data class Joined(val parts: List<UiText>, val separator: String) : UiText {
        override fun resolve(resources: Resources): String =
            parts.joinToString(separator) { it.resolve(resources) }
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

private fun resolveArgs(resources: Resources, args: List<Any>): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()

/** Resolves this text in the current configuration, so it follows a language change. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    // Read so a configuration change recomposes the caller even where Resources is the same object.
    LocalConfiguration.current
    return resolve(LocalResources.current)
}
