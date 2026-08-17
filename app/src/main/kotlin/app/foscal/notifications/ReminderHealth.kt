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
                    title = "Calendar access is off",
                    detail = "Foscal reads your reminders from the system calendar. Without access " +
                        "there is nothing to schedule.",
                    fix = ReminderFix.GRANT_CALENDAR,
                    fixLabel = "Grant access",
                ),
            )
        }
        if (!health.notificationsEnabled) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Notifications are turned off",
                    detail = "Alarms still fire, but Android discards the notification, so nothing " +
                        "reaches you.",
                    fix = ReminderFix.OPEN_NOTIFICATION_SETTINGS,
                    fixLabel = "Open notification settings",
                ),
            )
        }
        if (health.backgroundRestricted) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Foscal is restricted from running in the background",
                    detail = "Already-set alarms may still fire, but Foscal cannot re-read your " +
                        "calendar, so reminders stop within a week as the scheduled window runs out.",
                    fix = ReminderFix.OPEN_APP_SETTINGS,
                    fixLabel = "Open app settings",
                ),
            )
        }
        if (health.standbyBucket == StandbyBucket.RESTRICTED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = "Android has put Foscal in the restricted bucket",
                    detail = "In this bucket background work runs roughly once a day and alarms are " +
                        "heavily deferred. Opening Foscal now and then, or exempting it from " +
                        "battery optimization, moves it back out.",
                    fix = ReminderFix.OPEN_BATTERY_OPTIMIZATION,
                    fixLabel = "Battery settings",
                ),
            )
        }

        if (health.exactAlarms == ExactAlarmAccess.DENIED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Exact alarms are not allowed",
                    detail = "Reminders are still scheduled, but Android may deliver them minutes " +
                        "or hours late — it batches them with whenever the device next wakes.",
                    fix = ReminderFix.REQUEST_EXACT_ALARMS,
                    fixLabel = "Allow exact alarms",
                ),
            )
        }
        if (!health.ignoringBatteryOptimizations) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Battery optimization is on for Foscal",
                    detail = "Doze can hold reminders back until the device wakes up. Exempting " +
                        "Foscal costs almost nothing — it wakes only when a reminder is due.",
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
                    title = "Foscal is in Android's \"rare\" bucket",
                    detail = "Background work is limited because Foscal is opened seldom. Reminders " +
                        "may be refreshed less often than every few hours.",
                    fix = null,
                ),
            )
        }
        if (health.hasVendorAutostartScreen) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "This device has its own app-killing settings",
                    detail = "Some manufacturers stop background work regardless of Android's own " +
                        "settings. If reminders keep failing, allow Foscal to autostart and remove " +
                        "any extra battery restriction there.",
                    fix = ReminderFix.OPEN_VENDOR_AUTOSTART,
                    fixLabel = "Open device settings",
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
                    detail = "Existing reminders were deliberately left armed rather than cleared. " +
                        "Foscal will retry on its own; you can also force it now.",
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
                    title = "Reminders have never been scheduled",
                    detail = "No sync has completed on this device yet.",
                    fix = ReminderFix.RESYNC,
                    fixLabel = "Resync now",
                ),
            )
        }
        if (Duration.between(lastSync, now) > STALE_AFTER) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = "Reminders have not been refreshed recently",
                    detail = "The last successful sync was more than " +
                        "${STALE_AFTER.toHours()} hours ago, which usually means background work " +
                        "is being blocked.",
                    fix = ReminderFix.RESYNC,
                    fixLabel = "Resync now",
                ),
            )
        }
        return emptyList()
    }
}
