package app.foscal.notifications

import app.foscal.core.data.ReminderSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReminderHealthCheckTest {

    private val now: Instant = Instant.parse("2026-08-17T12:00:00Z")

    /** A device on which nothing at all is wrong. */
    private fun healthy(
        calendarPermission: Boolean = true,
        notificationsEnabled: Boolean = true,
        exactAlarms: ExactAlarmAccess = ExactAlarmAccess.GRANTED,
        ignoringBatteryOptimizations: Boolean = true,
        backgroundRestricted: Boolean = false,
        standbyBucket: StandbyBucket = StandbyBucket.ACTIVE,
        lastSyncAt: Instant? = now.minusSeconds(600),
        lastOutcome: ReminderSyncStatus.Outcome = ReminderSyncStatus.Outcome.Success,
        alarmsArmed: Int = 12,
        hasVendorAutostartScreen: Boolean = false,
    ) = ReminderHealth(
        calendarPermission = calendarPermission,
        notificationsEnabled = notificationsEnabled,
        exactAlarms = exactAlarms,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        backgroundRestricted = backgroundRestricted,
        standbyBucket = standbyBucket,
        lastSyncAt = lastSyncAt,
        lastOutcome = lastOutcome,
        alarmsArmed = alarmsArmed,
        hasVendorAutostartScreen = hasVendorAutostartScreen,
    )

    private fun issuesFor(health: ReminderHealth) = ReminderHealthCheck.issues(health, now)

    @Test
    fun `a healthy device reports nothing`() {
        assertTrue(issuesFor(healthy()).isEmpty())
    }

    /** Zero armed alarms is normal — an empty calendar, or every event already past. */
    @Test
    fun `no armed alarms is not by itself a problem`() {
        assertTrue(issuesFor(healthy(alarmsArmed = 0)).isEmpty())
    }

    @Test
    fun `missing calendar permission blocks`() {
        val issues = issuesFor(healthy(calendarPermission = false))
        assertEquals(1, issues.size)
        assertEquals(HealthSeverity.BLOCKING, issues.single().severity)
        assertEquals(ReminderFix.GRANT_CALENDAR, issues.single().fix)
    }

    /**
     * Without permission there is no sync to be stale, and reporting one would tell the user to fix
     * a symptom of the thing already listed above it.
     */
    @Test
    fun `missing calendar permission suppresses the stale-sync warning`() {
        val issues = issuesFor(healthy(calendarPermission = false, lastSyncAt = null))
        assertEquals(1, issues.size)
    }

    @Test
    fun `disabled notifications block`() {
        val issues = issuesFor(healthy(notificationsEnabled = false))
        assertEquals(ReminderFix.OPEN_NOTIFICATION_SETTINGS, issues.single().fix)
        assertEquals(HealthSeverity.BLOCKING, issues.single().severity)
    }

    @Test
    fun `background restriction blocks`() {
        val issues = issuesFor(healthy(backgroundRestricted = true))
        assertEquals(HealthSeverity.BLOCKING, issues.single().severity)
        assertEquals(ReminderFix.OPEN_APP_SETTINGS, issues.single().fix)
    }

    @Test
    fun `the restricted bucket blocks`() {
        val issues = issuesFor(healthy(standbyBucket = StandbyBucket.RESTRICTED))
        assertEquals(HealthSeverity.BLOCKING, issues.single().severity)
    }

    /**
     * RESTRICTED must not also match the "rare bucket" warning: one restriction, reported twice at
     * two different severities, reads as two separate faults.
     */
    @Test
    fun `the restricted bucket is reported once`() {
        val issues = issuesFor(healthy(standbyBucket = StandbyBucket.RESTRICTED))
        assertEquals(1, issues.size)
    }

    @Test
    fun `the rare bucket only warns`() {
        val issues = issuesFor(healthy(standbyBucket = StandbyBucket.RARE))
        assertEquals(HealthSeverity.DEGRADING, issues.single().severity)
    }

    @Test
    fun `ordinary buckets are silent`() {
        for (bucket in listOf(StandbyBucket.ACTIVE, StandbyBucket.WORKING_SET, StandbyBucket.FREQUENT)) {
            assertTrue("$bucket should be silent", issuesFor(healthy(standbyBucket = bucket)).isEmpty())
        }
    }

    /**
     * On Android 11 and below there are no buckets to read, and UNKNOWN must not be mistaken for a
     * restriction — that would show a permanent warning to every user on an older device.
     */
    @Test
    fun `an unknown bucket is silent`() {
        assertTrue(issuesFor(healthy(standbyBucket = StandbyBucket.UNKNOWN)).isEmpty())
    }

    @Test
    fun `denied exact alarms only warn because delivery still happens`() {
        val issues = issuesFor(healthy(exactAlarms = ExactAlarmAccess.DENIED))
        assertEquals(HealthSeverity.DEGRADING, issues.single().severity)
        assertEquals(ReminderFix.REQUEST_EXACT_ALARMS, issues.single().fix)
    }

    @Test
    fun `exact alarms below Android 12 are not an issue`() {
        assertTrue(issuesFor(healthy(exactAlarms = ExactAlarmAccess.NOT_APPLICABLE)).isEmpty())
    }

    @Test
    fun `battery optimization warns`() {
        val issues = issuesFor(healthy(ignoringBatteryOptimizations = false))
        assertEquals(HealthSeverity.DEGRADING, issues.single().severity)
        assertEquals(ReminderFix.OPEN_BATTERY_OPTIMIZATION, issues.single().fix)
    }

    @Test
    fun `a vendor autostart screen is offered when one exists`() {
        val issues = issuesFor(healthy(hasVendorAutostartScreen = true))
        assertEquals(ReminderFix.OPEN_VENDOR_AUTOSTART, issues.single().fix)
    }

    @Test
    fun `a failed read offers a resync`() {
        val issues = issuesFor(healthy(lastOutcome = ReminderSyncStatus.Outcome.ReadFailed))
        assertEquals(ReminderFix.RESYNC, issues.single().fix)
    }

    @Test
    fun `never having synced is reported`() {
        val issues = issuesFor(healthy(lastSyncAt = null))
        assertEquals(ReminderFix.RESYNC, issues.single().fix)
        assertTrue(issues.single().title.contains("never"))
    }

    @Test
    fun `a sync inside the stale window is fine`() {
        val fresh = now.minus(ReminderHealthCheck.STALE_AFTER).plusSeconds(60)
        assertTrue(issuesFor(healthy(lastSyncAt = fresh)).isEmpty())
    }

    @Test
    fun `a sync past the stale window is reported`() {
        val old = now.minus(ReminderHealthCheck.STALE_AFTER).minusSeconds(60)
        val issues = issuesFor(healthy(lastSyncAt = old))
        assertEquals(ReminderFix.RESYNC, issues.single().fix)
    }

    /**
     * A failed read is itself the reason the sync is old; reporting both would have the user chase
     * two entries with the same cause and the same button.
     */
    @Test
    fun `a failed read and a stale sync collapse to one entry`() {
        val issues = issuesFor(
            healthy(
                lastOutcome = ReminderSyncStatus.Outcome.ReadFailed,
                lastSyncAt = now.minus(ReminderHealthCheck.STALE_AFTER).minusSeconds(60),
            ),
        )
        assertEquals(1, issues.size)
    }

    @Test
    fun `blocking issues sort ahead of warnings`() {
        val issues = issuesFor(
            healthy(
                notificationsEnabled = false,
                exactAlarms = ExactAlarmAccess.DENIED,
                ignoringBatteryOptimizations = false,
                backgroundRestricted = true,
            ),
        )
        val firstWarning = issues.indexOfFirst { it.severity == HealthSeverity.DEGRADING }
        val lastBlocking = issues.indexOfLast { it.severity == HealthSeverity.BLOCKING }
        assertTrue(lastBlocking < firstWarning)
    }

    @Test
    fun `every issue that offers a fix labels its button`() {
        val issues = issuesFor(
            healthy(
                calendarPermission = false,
                notificationsEnabled = false,
                exactAlarms = ExactAlarmAccess.DENIED,
                ignoringBatteryOptimizations = false,
                backgroundRestricted = true,
                standbyBucket = StandbyBucket.RESTRICTED,
                hasVendorAutostartScreen = true,
            ),
        )
        assertFalse(issues.isEmpty())
        assertTrue(issues.filter { it.fix != null }.all { !it.fixLabel.isNullOrBlank() })
    }
}
