package app.foscal.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide, observable source of truth for whether the user has granted calendar access.
 *
 * The Calendar Provider throws [SecurityException] the moment it is touched without
 * READ_CALENDAR/WRITE_CALENDAR — including from [android.content.ContentResolver.registerContentObserver],
 * which is not a query and therefore easy to overlook. Everything that reads the provider
 * gates on [granted] so no provider call is made before permission exists, and so data and
 * reminders refresh automatically the instant the user grants it. Call [refresh] after any
 * permission result or in the activity's onResume.
 */
@Singleton
class CalendarPermissionState @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _granted = MutableStateFlow(hasPermission())
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    val isGranted: Boolean get() = _granted.value

    fun refresh() {
        _granted.value = hasPermission()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
    }
}
