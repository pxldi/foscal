package app.calendarium.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.core.data.CalendarPermissionState
import app.calendarium.core.data.CalendarRepository
import app.calendarium.core.data.UserPreferencesRepository
import app.calendarium.ui.CalendarColors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DAVxStatus { INSTALLED, NOT_INSTALLED }

data class OnboardingUiState(
    val completing: Boolean = false,
    val davxStatus: DAVxStatus = DAVxStatus.NOT_INSTALLED,
    val calendarPermissionGranted: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
    private val permissionState: CalendarPermissionState,
) : ViewModel() {

    private val _internal = MutableStateFlow(OnboardingUiState(davxStatus = davxStatus()))

    val state: StateFlow<OnboardingUiState> =
        combine(_internal, permissionState.granted) { internal, granted ->
            internal.copy(calendarPermissionGranted = granted)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            _internal.value,
        )

    /** Called after the system permission dialog returns, so the UI reflects the new grant. */
    fun onPermissionResult() {
        permissionState.refresh()
    }

    private fun davxStatus(): DAVxStatus {
        val pm = context.packageManager
        return try {
            pm.getPackageInfo(DAVX_PACKAGE, 0)
            DAVxStatus.INSTALLED
        } catch (e: PackageManager.NameNotFoundException) {
            DAVxStatus.NOT_INSTALLED
        }
    }

    fun openDavxOrStore(): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(DAVX_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_DAVX_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun useLocalOnly() {
        if (_internal.value.completing) return
        _internal.value = _internal.value.copy(completing = true, error = null)
        viewModelScope.launch {
            try {
                val color = CalendarColors.pick(0)
                val id = repository.createLocalCalendar(name = "My calendar", color = color)
                if (id == null) {
                    _internal.value = _internal.value.copy(
                        completing = false,
                        error = "Couldn't create a calendar. Please grant calendar access and try again.",
                    )
                    return@launch
                }
                finishOnboarding()
            } catch (t: Throwable) {
                _internal.value = _internal.value.copy(completing = false, error = t.message)
            }
        }
    }

    fun useExisting() {
        if (_internal.value.completing) return
        _internal.value = _internal.value.copy(completing = true, error = null)
        viewModelScope.launch { finishOnboarding() }
    }

    fun finishAfterSync() {
        viewModelScope.launch { finishOnboarding() }
    }

    private suspend fun finishOnboarding() {
        prefs.setOnboardingCompleted()
        _internal.value = _internal.value.copy(completing = false)
    }

    companion object {
        const val DAVX_PACKAGE = "at.bitfire.davdroid"
        const val FDROID_DAVX_URL = "https://f-droid.org/packages/at.bitfire.davdroid/"
    }
}
