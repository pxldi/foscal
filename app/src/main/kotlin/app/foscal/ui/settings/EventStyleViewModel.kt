package app.foscal.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.core.data.Preferences
import app.foscal.core.model.EventColorStrength
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How an event is drawn: the three choices the "Calendar style" page owns. */
data class EventStyleState(
    val colorStrength: EventColorStrength = EventColorStrength.Default,
    val textScalePercent: Int = Preferences.DEFAULT_EVENT_TEXT_SCALE,
    val wrapTitles: Boolean = true,
)

/**
 * Kept apart from [app.foscal.ui.home.BehaviourViewModel], which is deliberately about how the
 * calendar *behaves*. These three are only about how it looks, and they are also the ones the
 * settings page has to render a live sample from.
 */
@HiltViewModel
class EventStyleViewModel @Inject constructor(
    private val prefs: Preferences,
) : ViewModel() {

    val state: StateFlow<EventStyleState> = combine(
        prefs.eventColorStrength,
        prefs.eventTextScalePercent,
        prefs.wrapEventTitles,
    ) { strength, scale, wrap ->
        EventStyleState(colorStrength = strength, textScalePercent = scale, wrapTitles = wrap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventStyleState())

    fun setColorStrength(strength: EventColorStrength) {
        viewModelScope.launch { prefs.setEventColorStrength(strength) }
    }

    fun setTextScalePercent(percent: Int) {
        viewModelScope.launch { prefs.setEventTextScalePercent(percent) }
    }

    fun setWrapTitles(wrap: Boolean) {
        viewModelScope.launch { prefs.setWrapEventTitles(wrap) }
    }
}
