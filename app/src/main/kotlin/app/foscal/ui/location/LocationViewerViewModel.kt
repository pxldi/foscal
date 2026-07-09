package app.foscal.ui.location

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foscal.location.NominatimGeocoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocationViewerState(
    val loading: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String = "",
    /** True when the location text couldn't be geocoded to a point. */
    val notFound: Boolean = false,
)

/**
 * Read-only counterpart to [LocationPickerViewModel]: forward-geocodes an event's stored location
 * text so the detail screen can show it on an in-app OpenStreetMap instead of leaving the app.
 */
@HiltViewModel
class LocationViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val geocoder: NominatimGeocoder,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationViewerState())
    val state: StateFlow<LocationViewerState> = _state.asStateFlow()

    val locationText: String = savedStateHandle.get<String>("location").orEmpty()

    init {
        viewModelScope.launch {
            val place = geocoder.search(locationText)
            _state.update {
                if (place == null) {
                    it.copy(loading = false, notFound = true)
                } else {
                    it.copy(
                        loading = false,
                        latitude = place.latitude,
                        longitude = place.longitude,
                        label = place.displayName.ifBlank { locationText },
                    )
                }
            }
        }
    }
}
