package app.foscal.notifications

import app.foscal.R
import app.foscal.core.data.ReminderSyncStatus
import app.foscal.ui.util.UiText
import app.foscal.ui.util.uiPlural
import app.foscal.ui.util.uiText
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

/** The texts are resolved where they are shown, so the decisions here stay testable on the JVM. */
data class ReminderIssue(
    val severity: HealthSeverity,
    val title: UiText,
    val detail: UiText,
    val fix: ReminderFix?,
    val fixLabel: UiText? = null,
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
                    title = uiText(R.string.health_no_calendar_title),
                    detail = uiText(R.string.health_no_calendar_detail),
                    fix = ReminderFix.GRANT_CALENDAR,
                    fixLabel = uiText(R.string.health_no_calendar_fix),
                ),
            )
        }
        if (!health.notificationsEnabled) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = uiText(R.string.health_notifications_off_title),
                    detail = uiText(R.string.health_notifications_off_detail),
                    fix = ReminderFix.OPEN_NOTIFICATION_SETTINGS,
                    fixLabel = uiText(R.string.health_notifications_off_fix),
                ),
            )
        }
        if (health.backgroundRestricted) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = uiText(R.string.health_background_blocked_title),
                    detail = uiText(R.string.health_background_blocked_detail),
                    fix = ReminderFix.OPEN_APP_SETTINGS,
                    fixLabel = uiText(R.string.health_app_settings_fix),
                ),
            )
        }
        if (health.standbyBucket == StandbyBucket.RESTRICTED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.BLOCKING,
                    title = uiText(R.string.health_restricted_title),
                    detail = uiText(R.string.health_restricted_detail),
                    fix = ReminderFix.OPEN_BATTERY_OPTIMIZATION,
                    fixLabel = uiText(R.string.health_battery_settings_fix),
                ),
            )
        }

        if (health.exactAlarms == ExactAlarmAccess.DENIED) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_exact_alarms_title),
                    detail = uiText(R.string.health_exact_alarms_detail),
                    fix = ReminderFix.REQUEST_EXACT_ALARMS,
                    fixLabel = uiText(R.string.health_exact_alarms_fix),
                ),
            )
        }
        if (!health.ignoringBatteryOptimizations) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_battery_title),
                    detail = uiText(R.string.health_battery_detail),
                    fix = ReminderFix.OPEN_BATTERY_OPTIMIZATION,
                    fixLabel = uiText(R.string.health_battery_settings_fix),
                ),
            )
        }
        // RESTRICTED already produced a blocking entry above; repeating it here as a warning would
        // show the same problem twice with two different severities.
        if (health.standbyBucket == StandbyBucket.RARE) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_rare_title),
                    detail = uiText(R.string.health_rare_detail),
                    fix = null,
                ),
            )
        }
        if (health.hasVendorAutostartScreen) {
            add(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_vendor_title),
                    detail = uiText(R.string.health_vendor_detail),
                    fix = ReminderFix.OPEN_VENDOR_AUTOSTART,
                    fixLabel = uiText(R.string.health_vendor_fix),
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
                    title = uiText(R.string.health_read_failed_title),
                    detail = uiText(R.string.health_read_failed_detail),
                    fix = ReminderFix.RESYNC,
                    fixLabel = uiText(R.string.health_resync_fix),
                ),
            )
        }
        val lastSync = health.lastSyncAt
        if (lastSync == null) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_never_synced_title),
                    detail = uiText(R.string.health_never_synced_detail),
                    fix = ReminderFix.RESYNC,
                    fixLabel = uiText(R.string.health_resync_fix),
                ),
            )
        }
        if (Duration.between(lastSync, now) > STALE_AFTER) {
            return listOf(
                ReminderIssue(
                    severity = HealthSeverity.DEGRADING,
                    title = uiText(R.string.health_stale_title),
                    detail = uiPlural(R.plurals.health_stale_detail, STALE_AFTER.toHours().toInt()),
                    fix = ReminderFix.RESYNC,
                    fixLabel = uiText(R.string.health_resync_fix),
                ),
            )
        }
        return emptyList()
    }
}
