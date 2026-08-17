package app.foscal.notifications

import app.foscal.core.data.ReminderSyncStatus
import java.time.Duration
import java.time.Instant

/**
 * Why a reminder might not arrive, ordered by how badly it hurts.
 *
 * [BLOCKING] means no reminder will be delivered at all until it is fixed; [DEGRADING] means they
 * still arrive but can be late or go stale.
 */
enum class HealthSeverity { BLOCKING, DEGRADING }

/** Whether the app may set alarms that fire at a precise minute. */
enum class ExactAlarmAccess {
    GRANTED,
    DENIED,

    /** Below Android 12, where exact alarms need no permission. */
    NOT_APPLICABLE,
}

/**
 * The system's opinion of how often this app is used, which decides how much background work it is
 * allowed. Mirrors `UsageStatsManager`'s buckets, plus [UNKNOWN] for releases that predate them.
 */
enum class StandbyBucket { ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED, UNKNOWN }

/** Everything the diagnostics screen needs, gathered in one pass. */
data class ReminderHealth(
    val calendarPermission: Boolean,
    val notificationsEnabled: Boolean,
    val exactAlarms: ExactAlarmAccess,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val standbyBucket: StandbyBucket,
    val lastSyncAt: Instant?,
    val lastOutcome: ReminderSyncStatus.Outcome,
    val alarmsArmed: Int,
    /** Whether this device offers a vendor "autostart" screen we know how to open. */
    val hasVendorAutostartScreen: Boolean,
)

/** What tapping an issue's button should do. */
enum class ReminderFix {
    GRANT_CALENDAR,
    OPEN_NOTIFICATION_SETTINGS,
    REQUEST_EXACT_ALARMS,
    OPEN_BATTERY_OPTIMIZATION,
    OPEN_APP_SETTINGS,
    OPEN_VENDOR_AUTOSTART,
    RESYNC,
}

data class ReminderIssue(
    val severity: HealthSeverity,
    val title: String,
    val detail: String,
    val fix: ReminderFix?,
    val fixLabel: String? = null,
)

/**
 * Turns a [ReminderHealth] into the list of things actually wrong with it.
 *
 * Separated from the probing so the rules — which of these is fatal, which is merely a warning, how
 * stale is stale — can be tested exhaustively without a device, an emulator or Robolectric. The
 * probe that fills in a [ReminderHealth] is all Android calls and no decisions; this is all
 * decisions and no Android.
 */
object ReminderHealthCheck {

    /**
     * How long without a successful sync counts as stale.
     *
     * The backstop worker runs every 6 hours, and WorkManager is allowed to defer a periodic run
     * well past its interval on a dozing device. Twice the interval is late enough to mean
     * something is genuinely wrong rather than that the phone spent the night in a drawer.
     */
    val STALE_AFTER: Duration = Duration.ofHours(12)

    fun issues(health: ReminderHealth, now: Instant): List<ReminderIssue> = buildList {
        if (!health.calendarPermission) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "No calendar access",
                    detail = "Foscal can't read your events, so there's nothing to remind you about.",
                    fix = ReminderFix.GRANT_CALENDAR,
                    fixLabel = "Grant access",
                ),
            )
        }
        if (!health.notificationsEnabled) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Notifications are off",
                    detail = "Alarms still fire, but Android throws the notification away.",
                    fix = ReminderFix.OPEN_NOTIFICATION_SETTINGS,
                    fixLabel = "Open notifications",
                ),
            )
        }
        if (health.backgroundRestricted) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Background use is blocked",
                    detail = "Foscal can't re-read your calendar, so reminders run out within a week.",
                    fix = ReminderFix.OPEN_APP_SETTINGS,
                    fixLabel = "App settings",
                ),
            )
        }
        if (health.standbyBucket == StandbyBucket.RESTRICTED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Android has restricted Foscal",
                    detail = "Background work runs about once a day here. Opening Foscal now and " +
                        "then, or turning off battery optimization, gets it out.",
                    fix = ReminderFix.OPEN_BATTERY_OPTIMIZATION,
                    fixLabel = "Battery settings",
                ),
            )
        }

        if (health.exactAlarms == ExactAlarmAccess.DENIED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Exact alarms are off",
                    detail = "Reminders still work, but Android may deliver them minutes or hours late.",
                    fix = ReminderFix.REQUEST_EXACT_ALARMS,
                    fixLabel = "Allow exact alarms",
                ),
            )
        }
        if (!health.ignoringBatteryOptimizations) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Battery optimization is on",
                    detail = "It can hold reminders back until the phone wakes up. Foscal only " +
                        "wakes when one is due.",
                    fix = ReminderFix.OPEN_BATTERY_OPTIMIZATION,
                    fixLabel = "Battery settings",
                ),
            )
        }
        // RESTRICTED already produced a blocking entry above; repeating it here as a warning would
        // show the same problem twice with two different severities.
        if (health.standbyBucket == StandbyBucket.RARE) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Foscal is opened rarely",
                    detail = "Android limits background work for apps you seldom use, so reminders " +
                        "refresh less often.",
                    fix = null,
                ),
            )
        }
        if (health.hasVendorAutostartScreen) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "This phone kills background apps",
                    detail = "Some manufacturers stop apps whatever Android says. If reminders keep " +
                        "failing, allow Foscal to autostart.",
                    fix = ReminderFix.OPEN_VENDOR_AUTOSTART,
                    fixLabel = "Device settings",
                ),
            )
        }

        addAll(syncIssues(health, now))
    }

    private fun syncIssues(health: ReminderHealth, now: Instant): List<ReminderIssue> {
        // Permission is already reported as blocking; a sync that stopped because of it is that same
        // fact restated.
        if (!health.calendarPermission) return emptyList()

        if (health.lastOutcome == ReminderSyncStatus.Outcome.ReadFailed) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "The last calendar read failed",
                    detail = "Your existing reminders were left alone. Foscal will retry on its own.",
                    fix = ReminderFix.RESYNC,
                    fixLabel = "Resync now",
                ),
            )
        }
        val lastSync = health.lastSyncAt
        if (lastSync == null) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Reminders have never been set up",
                    detail = "No sync has finished on this phone yet.",
                    fix = ReminderFix.RESYNC,
                    fixLabel = "Resync now",
                ),
            )
        }
        if (Duration.between(lastSync, now) > STALE_AFTER) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Reminders are out of date",
                    detail = "Nothing has synced for over ${STALE_AFTER.toHours()} hours, which " +
                        "usually means background work is being blocked.",
                    fix = ReminderFix.RESYNC,
                    fixLabel = "Resync now",
                ),
            )
        }
        return emptyList()
    }
}
