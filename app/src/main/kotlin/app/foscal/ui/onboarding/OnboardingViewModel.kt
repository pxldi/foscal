package app.foscal.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.CalendarPermissionState
import app.foscal.core.data.CalendarRepository
import app.foscal.core.data.UserPreferencesRepository
import app.foscal.core.model.AccentColor
import app.foscal.core.model.ThemeMode
import app.foscal.ui.CalendarColors
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
    val setupComplete: Boolean = false,
    val finished: Boolean = false,
    val davxStatus: DAVxStatus = DAVxStatus.NOT_INSTALLED,
    val calendarPermissionGranted: Boolean = false,
    val mapsEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColor: AccentColor = AccentColor.Default,
    val accentCustomColor: Int = AccentColor.DEFAULT_CUSTOM_COLOR,
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: CalendarRepository,
    private val prefs: UserPreferencesRepository,
    private val permissionState: CalendarPermissionState,
) : ViewModel() {

    private val _internal = MutableStateFlow(OnboardingUiState(davxStatus = davxStatus()))

    init {
        viewModelScope.launch {
            prefs.themeMode.collect { mode -> _internal.value = _internal.value.copy(themeMode = mode) }
        }
        viewModelScope.launch {
            prefs.accentColor.collect { accent -> _internal.value = _internal.value.copy(accentColor = accent) }
        }
        viewModelScope.launch {
            prefs.accentCustomColor.collect { color ->
                _internal.value = _internal.value.copy(accentCustomColor = color)
            }
        }
    }

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

    /** Opt into the OpenStreetMap location picker (the only networked feature). Off by default. */
    fun setMapsEnabled(enabled: Boolean) {
        _internal.value = _internal.value.copy(mapsEnabled = enabled)
        viewModelScope.launch { prefs.setOsmMapsEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _internal.value = _internal.value.copy(themeMode = mode)
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setAccentColor(accent: AccentColor) {
        _internal.value = _internal.value.copy(accentColor = accent)
        viewModelScope.launch { prefs.setAccentColor(accent) }
    }

    fun setCustomAccentColor(color: Int) {
        _internal.value = _internal.value.copy(accentColor = AccentColor.CUSTOM, accentCustomColor = color)
        viewModelScope.launch {
            prefs.setAccentCustomColor(color)
            prefs.setAccentColor(AccentColor.CUSTOM)
        }
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
                _internal.value = _internal.value.copy(
                    completing = false,
                    setupComplete = true,
                )
            } catch (t: Throwable) {
                _internal.value = _internal.value.copy(completing = false, error = t.message)
            }
        }
    }

    fun useExisting() {
        if (_internal.value.completing) return
        _internal.value = _internal.value.copy(completing = true, error = null)
        viewModelScope.launch {
            _internal.value = _internal.value.copy(
                completing = false,
                setupComplete = true,
            )
        }
    }

    fun finishAfterSync() {
        _internal.value = _internal.value.copy(setupComplete = true)
    }

    fun completeOnboarding() {
        if (_internal.value.completing) return
        _internal.value = _internal.value.copy(completing = true, error = null)
        viewModelScope.launch { finishOnboarding() }
    }

    private suspend fun finishOnboarding() {
        prefs.setOnboardingCompleted()
        _internal.value = _internal.value.copy(completing = false, finished = true)
    }

    companion object {
        const val DAVX_PACKAGE = "at.bitfire.davdroid"
        const val FDROID_DAVX_URL = "https://f-droid.org/packages/at.bitfire.davdroid/"
    }
}
