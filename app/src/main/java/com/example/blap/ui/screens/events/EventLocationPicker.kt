package com.example.blap.ui.screens.events

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blap.R
import com.example.blap.location.GeoCoordinates
import com.example.blap.location.LocationFix
import com.example.blap.location.PlaceSearchResult
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlinx.coroutines.launch

internal data class EventLocationSelection(
    val venueName: String,
    val coordinates: GeoCoordinates,
)

@Composable
internal fun EventLocationPicker(
    selection: EventLocationSelection?,
    radiusMetres: Double,
    onSelectionChanged: (EventLocationSelection) -> Unit,
    getCurrentLocation: suspend () -> LocationFix?,
    searchPlaces: suspend (String) -> List<PlaceSearchResult>,
    addressForCoordinates: suspend (GeoCoordinates) -> String?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locationError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(selection?.venueName) { mutableStateOf(selection?.venueName.orEmpty()) }
    var searchResults by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    fun loadCurrentLocation() {
        if (locating) return
        locating = true
        locationError = null
        scope.launch {
            runCatching { getCurrentLocation() }
                .onSuccess { location ->
                    if (location == null || !location.coordinates.isValid) {
                        locationError = "Current location was not available."
                    } else {
                        val address = runCatching {
                            addressForCoordinates(location.coordinates)
                        }.getOrNull()?.takeIf(String::isNotBlank)
                        val updated = EventLocationSelection(
                            address ?: location.coordinates.displayLabel(),
                            location.coordinates,
                        )
                        onSelectionChanged(updated)
                        searchQuery = updated.venueName
                        searchResults = emptyList()
                        if (address == null) {
                            locationError = "Location found, but its address could not be loaded. You can edit it below."
                        }
                    }
                }
                .onFailure { locationError = "Current location was not available." }
            locating = false
        }
    }

    fun searchLocations() {
        val query = searchQuery.trim()
        if (query.length < MIN_SEARCH_LENGTH || searching) return
        searching = true
        locationError = null
        scope.launch {
            runCatching { searchPlaces(query) }
                .onSuccess { results ->
                    searchResults = results
                    if (results.isEmpty()) locationError = "No matching locations found."
                }
                .onFailure { locationError = it.toLocationSearchMessage() }
            searching = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) loadCurrentLocation()
        else locationError = "Allow location access to use your current position."
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Search for the venue or pin the location on the map.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(MAX_VENUE_NAME_LENGTH) },
                placeholder = { Text("Search location") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = ::searchLocations,
                enabled = searchQuery.trim().length >= MIN_SEARCH_LENGTH && !searching,
            ) {
                Text(if (searching) "Searching…" else "Search")
            }
        }
        if (searchResults.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                searchResults.forEach { result ->
                    Card(
                        onClick = {
                            val updated = EventLocationSelection(result.displayName, result.coordinates)
                            onSelectionChanged(updated)
                            searchQuery = updated.venueName
                            searchResults = emptyList()
                            locationError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(result.displayName, modifier = Modifier.padding(12.dp), maxLines = 3)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) loadCurrentLocation()
                else locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(
                1.dp,
                if (locating) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_location),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (locating) "Finding location…" else "Use Current Location",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OsmEventMap(
            point = selection?.coordinates,
            radiusMetres = radiusMetres,
            onPointSelected = { coordinates ->
                val updated = EventLocationSelection("Pinned location", coordinates)
                onSelectionChanged(updated)
                searchQuery = updated.venueName
                searchResults = emptyList()
                locationError = null
            },
            height = 210.dp,
        )
        selection?.let { current ->
            OutlinedTextField(
                value = current.venueName,
                onValueChange = { name ->
                    onSelectionChanged(current.copy(venueName = name.take(MAX_VENUE_NAME_LENGTH)))
                },
                label = { Text("Venue Address") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        locationError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
    }
}

private fun Throwable.toLocationSearchMessage(): String = when {
    generateSequence(this) { it.cause }.any { it is UnknownHostException } ->
        "No internet connection. Connect this phone to Wi-Fi or mobile data and try again."

    generateSequence(this) { it.cause }.any { it is SocketTimeoutException } ->
        "Location search timed out. Check the phone's internet connection and try again."

    else -> localizedMessage ?: "Location search failed."
}

private fun GeoCoordinates.displayLabel(): String = String.format(
    Locale.US,
    "%.5f, %.5f",
    latitude,
    longitude,
)

private const val MIN_SEARCH_LENGTH = 3
private const val MAX_VENUE_NAME_LENGTH = 200
