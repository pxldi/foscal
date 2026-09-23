package app.foscal.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foscal.ui.feedback.FeedbackSnackbarHost
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

// A wide, ocean-free default view for when there's no text or device location to center on.
private const val DEFAULT_LATITUDE = 30.0
private const val DEFAULT_LONGITUDE = 0.0
private const val DEFAULT_ZOOM = 3.5
private const val DEVICE_ZOOM = 14.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerRoute(
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    viewModel: LocationPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Return the resolved place to the editor and pop.
    LaunchedEffect(state.result) {
        state.result?.let(onConfirm)
    }

    // osmdroid refuses tile requests without a descriptive User-Agent; keep its cache app-internal
    // so no storage permission is needed.
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
        }
    }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
        }
    }

    // Apply any recenter requested by the view model (initial query or a search) exactly once each.
    LaunchedEffect(state.pendingCenter) {
        state.pendingCenter?.let { target ->
            mapView.controller.setZoom(target.zoom)
            mapView.controller.setCenter(GeoPoint(target.latitude, target.longitude))
            viewModel.onCenterConsumed()
        }
    }

    // When there's no query to center on, offer to center on the device's current area.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) centerOnDeviceLocation(context, mapView)
        viewModel.onDeviceLocationConsumed()
    }
    LaunchedEffect(state.useDeviceLocation) {
        if (state.useDeviceLocation) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                centerOnDeviceLocation(context, mapView)
                viewModel.onDeviceLocationConsumed()
            } else {
                locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Scaffold(
        snackbarHost = { FeedbackSnackbarHost() },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Pick location",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // Search box floating over the top of the map.
            var query by remember { mutableStateOf("") }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search for a place or address") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    isError = state.searchNotFound,
                    supportingText = if (state.searchNotFound) {
                        { Text("No match found") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            viewModel.search(query)
                        },
                    ),
                )
            }

            // A fixed centre pin: the map moves under it, so whatever sits at screen centre is the
            // chosen point. Shift the icon up by half its height so its tip marks the exact centre.
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .graphicsLayer { translationY = -size.height / 2f },
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val center = mapView.mapCenter
                            Toast.makeText(context, "Looking up address…", Toast.LENGTH_SHORT).show()
                            viewModel.confirm(center.latitude, center.longitude)
                        },
                        enabled = !state.resolving && !state.preparing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.resolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Use this location")
                    }
                }
            }

            if (state.preparing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/** Centers [mapView] on the device's last-known location if one is available and permitted. */
private fun centerOnDeviceLocation(context: Context, mapView: MapView) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return
    // Framework LocationManager (not Play Services) keeps this FOSS-friendly. Prefer whichever
    // provider has a recent fix; a last-known location is enough to open the map in the right area.
    val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    val location = providers.firstNotNullOfOrNull { provider ->
        try {
            if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
        } catch (_: SecurityException) {
            null
        }
    } ?: return
    mapView.controller.setZoom(DEVICE_ZOOM)
    mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
}
