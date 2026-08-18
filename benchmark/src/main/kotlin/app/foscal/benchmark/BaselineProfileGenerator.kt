package app.foscal.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records what the app actually runs on the way to a usable calendar.
 *
 * The output is a list of classes and methods ART compiles ahead of time at install, which is the
 * difference between a first session that JITs the whole Compose UI while the user is swiping and
 * one that does not. It is generated on a device here and committed, so ordinary release builds —
 * including CI — never need a device of their own.
 *
 * A profile is only worth what its journey covers, so the journey is the boring one everybody
 * takes: get past whatever a fresh install puts in the way, wait for the calendar, and page it.
 * Paging is the interaction people do most and the one that was reported as janky.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndPaging() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
        // Six passes rather than the default fifteen. The journey is re-run until the recorded
        // profile stops changing, and each pass costs an install, a cold start and four swipes;
        // the last nine passes were adding minutes and almost no methods.
        maxIterations = 6,
        stableIterations = 2,
    ) {
        // Granted here rather than tapped. The permission dialog belongs to the permission
        // controller, not to us: its button text changes with the Android version and the device
        // locale, and a journey that fails to find the right one does not fail — it stalls on a
        // screen the app itself cannot dismiss, which is exactly what happened.
        PERMISSIONS.forEach { device.executeShellCommand("pm grant $PACKAGE $it") }

        pressHome()
        startActivityAndWait()

        // Onboarding only stands in the way on the first pass, so every step is optional: if the
        // first run stops asking these questions the journey should record less, not fail.
        device.tapIfPresent("Get started")
        // A bare test device has no accounts on it, so "use what is already here" would finish
        // onboarding with nothing to draw. Creating the local calendar gives the grid something
        // real, and falls back to the other path if that card ever goes away.
        device.tapIfPresent("Create calendar")
        device.tapIfPresent("Continue")
        device.tapIfPresent("Start using Foscal")
        device.wait(Until.hasObject(By.pkg(PACKAGE)), TIMEOUT_MILLIS)
        device.waitForIdle()

        // Page sideways so the swipe path, its transition and the query behind them are compiled
        // before the user's first swipe rather than during it.
        repeat(4) {
            device.swipe(
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                device.displayWidth / 5,
                device.displayHeight / 2,
                10,
            )
            device.waitForIdle()
        }
    }

    /**
     * Taps [label] if this screen has it, scrolling to look for it before giving up.
     *
     * The scroll is not optional politeness. Onboarding is a scrolling column and its button sits
     * at the bottom of it, so on a short screen the button is not merely off-screen — it is not in
     * the accessibility tree at all, and a journey that only looks at what is visible walks past
     * the end of onboarding without ever finishing it.
     */
    private fun UiDevice.tapIfPresent(label: String) {
        val selector = By.text(label)
        if (!wait(Until.hasObject(selector), STEP_MILLIS)) {
            var scrolls = 0
            while (scrolls < SCROLL_TRIES && !hasObject(selector)) {
                swipe(displayWidth / 2, displayHeight * 3 / 4, displayWidth / 2, displayHeight / 4, 10)
                waitForIdle()
                scrolls++
            }
        }
        findObject(selector)?.click()
        waitForIdle()
    }

    private companion object {
        const val PACKAGE = "app.foscal"
        const val TIMEOUT_MILLIS = 10_000L
        const val STEP_MILLIS = 1_500L
        const val SCROLL_TRIES = 4

        val PERMISSIONS = listOf(
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
