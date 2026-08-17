package app.foscal.notifications

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.data.ReminderSyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device's current opinion of how much Foscal is allowed to do.
 *
 * All Android calls, no decisions — [ReminderHealthCheck] holds the rules about what is fatal and
 * what is merely a warning, so those can be tested without any of this.
 */
@Singleton
class ReminderHealthProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val permission: CalendarPermissionState,
    private val status: ReminderSyncStatus,
) {

    /**
     * Off the main thread deliberately. This looks like a handful of getters, but it is a DataStore
     * read from disk plus a dozen-odd binder round trips to the notification, alarm, power, activity
     * and package managers — enough to drop frames on a healthy phone and to hang the settings
     * screen on a slow one. `viewModelScope` runs on the main dispatcher, so the switch has to
     * happen here rather than being left to each caller to remember.
     */
    suspend fun probe(): ReminderHealth = withContext(Dispatchers.IO) {
        permission.refresh()
        val snapshot = status.snapshot.first()
        ReminderHealth(
            calendarPermission = permission.isGranted,
            notificationsEnabled = notificationsEnabled(),
            exactAlarms = exactAlarmAccess(),
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            backgroundRestricted = backgroundRestricted(),
            standbyBucket = standbyBucket(),
            lastSyncAt = snapshot.lastSyncAt,
            lastOutcome = snapshot.outcome,
            alarmsArmed = snapshot.alarmsArmed,
            hasVendorAutostartScreen = VendorSettings.autostartIntent(context) != null,
        )
    }

    /**
     * Whether a posted reminder would actually be shown.
     *
     * Both halves matter: `areNotificationsEnabled` covers the app-level switch and Android 13's
     * runtime grant, while a channel muted to IMPORTANCE_NONE leaves that true and still swallows
     * every notification — the more common state, because it is one tap away from a notification
     * the user found annoying.
     */
    private fun notificationsEnabled(): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(ReminderAlarmReceiver.CHANNEL_ID)
        // A null channel means it has not been created yet, which is not a fault the user can fix.
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun exactAlarmAccess(): ExactAlarmAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ExactAlarmAccess.NOT_APPLICABLE
        val am = context.getSystemService(AlarmManager::class.java)
            ?: return ExactAlarmAccess.NOT_APPLICABLE
        return if (am.canScheduleExactAlarms()) ExactAlarmAccess.GRANTED else ExactAlarmAccess.DENIED
    }

    private fun ignoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun backgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.isBackgroundRestricted
    }

    private fun standbyBucket(): StandbyBucket {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return StandbyBucket.UNKNOWN
        val usage = context.getSystemService(UsageStatsManager::class.java)
            ?: return StandbyBucket.UNKNOWN
        // The no-argument overload reports the caller's own bucket and needs no permission; the
        // by-package form would require PACKAGE_USAGE_STATS, which this app has no business holding.
        return when (usage.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
            // RESTRICTED is API 30; comparing numerically avoids a second version guard for a
            // constant that simply does not exist on 28 and 29.
            in RESTRICTED_BUCKET..Int.MAX_VALUE -> StandbyBucket.RESTRICTED
            else -> StandbyBucket.UNKNOWN
        }
    }

    private companion object {
        /** `UsageStatsManager.STANDBY_BUCKET_RESTRICTED`, which was added in API 30. */
        const val RESTRICTED_BUCKET = 45
    }
}

/**
 * The screens a user has to visit to undo each restriction.
 *
 * Returns null rather than throwing when a screen does not exist on this device — several of these
 * are optional, vendor-specific, or version-gated, and a diagnostics panel that crashes is worse
 * than one that offers one fewer button.
 */
object ReminderFixIntents {

    fun intentFor(context: Context, fix: ReminderFix): Intent? = when (fix) {
        ReminderFix.GRANT_CALENDAR, ReminderFix.RESYNC -> null // handled in-app, not by an Intent
        ReminderFix.OPEN_NOTIFICATION_SETTINGS -> notificationSettings(context)
        ReminderFix.REQUEST_EXACT_ALARMS -> exactAlarmSettings(context)
        ReminderFix.OPEN_BATTERY_OPTIMIZATION -> batteryOptimizationSettings()
        ReminderFix.OPEN_APP_SETTINGS -> appSettings(context)
        ReminderFix.OPEN_VENDOR_AUTOSTART -> VendorSettings.autostartIntent(context)
    }

    private fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun exactAlarmSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            "package:${context.packageName}".toUri(),
        )
    }

    /**
     * The battery-optimization *list*, not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
     *
     * The direct request shows a one-tap system dialog, which is why it is tempting — but it needs
     * the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which store policy treats as
     * restricted and grants only to a narrow set of app categories. The list screen needs no
     * permission at all; the user picks Foscal from it, which is one extra tap and no policy risk.
     */
    private fun batteryOptimizationSettings(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private fun appSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
}

/**
 * Manufacturer-specific "autostart" and app-killer screens.
 *
 * Several vendors ship background restrictions that are entirely outside Android's own model:
 * exempting Foscal from Doze and giving it exact-alarm access changes nothing if MIUI's autostart
 * list has it switched off. There is no API for any of this, so the only way to help is to try to
 * open the screen by explicit component and see whether it exists — hence the resolve check, and
 * hence the matching `<queries>` entries in the manifest, without which package-visibility
 * filtering makes every one of these look absent on Android 11 and up.
 */
object VendorSettings {

    private val CANDIDATES = listOf(
        // Xiaomi / Redmi / POCO
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / Realme
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // OnePlus
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Asus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        // Letv
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        // HTC
        "com.htc.pitroad" to "com.htc.pitroad.landingpage.activity.LandingPageActivity",
    )

    // Resolved once. Probing costs one binder call to the package manager per candidate, the answer
    // is a property of the ROM rather than of anything the user can change, and the probe re-runs on
    // every settings resume.
    @Volatile
    private var resolved: Result? = null

    private class Result(val intent: Intent?)

    fun autostartIntent(context: Context): Intent? {
        resolved?.let { return it.intent }
        val pm = context.packageManager
        val found = CANDIDATES
            .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
            .firstOrNull { it.resolveActivity(pm) != null }
        resolved = Result(found)
        return found
    }
}
