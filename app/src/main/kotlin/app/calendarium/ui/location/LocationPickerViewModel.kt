package app.calendarium.ui.location

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.calendarium.location.NominatimGeocoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A one-shot request to recenter the map, consumed by the screen once applied. */
data class GeoTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
)

data class LocationPickerState(
    /** True while forward-geocoding the initial query to decide where to center the map. */
    val preparing: Boolean = true,
    /** True while forward-geocoding a search typed into the picker. */
    val searching: Boolean = false,
    /** Set when the last search returned nothing, so the screen can say so. */
    val searchNotFound: Boolean = false,
    /** True while reverse-geocoding the confirmed pin. */
    val resolving: Boolean = false,
    /** When true the screen may center on the device's last-known location (no initial query). */
    val useDeviceLocation: Boolean = false,
    /** A pending recenter for the map; the screen applies it then calls [onCenterConsumed]. */
    val pendingCenter: GeoTarget? = null,
    /** Set once a place is chosen; the screen returns it to the editor and pops. */
    val result: String? = null,
)

@HiltViewModel
class LocationPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val geocoder: NominatimGeocoder,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationPickerState())
    val state: StateFlow<LocationPickerState> = _state.asStateFlow()

    init {
        // If the editor already had location text, jump the map near it; otherwise fall back to the
        // device's location (the screen handles the permission) and finally a wide default view.
        val query = savedStateHandle.get<String>("query").orEmpty()
        if (query.isBlank()) {
            _state.update { it.copy(preparing = false, useDeviceLocation = true) }
        } else {
            viewModelScope.launch {
                val place = geocoder.search(query)
                _state.update {
                    it.copy(
                        preparing = false,
                        useDeviceLocation = place == null,
                        pendingCenter = place?.let { p -> GeoTarget(p.latitude, p.longitude, PLACE_ZOOM) },
                    )
                }
            }
        }
    }

    /** Forward-geocodes free text typed into the picker and recenters the map on the best match. */
    fun search(query: String) {
        if (query.isBlank() || _state.value.searching) return
        _state.update { it.copy(searching = true, searchNotFound = false) }
        viewModelScope.launch {
            val place = geocoder.search(query)
            _state.update {
                it.copy(
                    searching = false,
                    searchNotFound = place == null,
                    pendingCenter = place?.let { p -> GeoTarget(p.latitude, p.longitude, PLACE_ZOOM) },
                )
            }
        }
    }

    fun onCenterConsumed() {
        _state.update { it.copy(pendingCenter = null) }
    }

    fun onDeviceLocationConsumed() {
        _state.update { it.copy(useDeviceLocation = false) }
    }

    /** Reverse-geocodes the confirmed map center, falling back to raw coordinates on failure. */
    fun confirm(latitude: Double, longitude: Double) {
        if (_state.value.resolving) return
        _state.update { it.copy(resolving = true) }
        viewModelScope.launch {
            val name = geocoder.reverse(latitude, longitude)
                ?: "%.5f, %.5f".format(latitude, longitude)
            _state.update { it.copy(resolving = false, result = name) }
        }
    }

    private companion object {
        const val PLACE_ZOOM = 15.0
    }
}
