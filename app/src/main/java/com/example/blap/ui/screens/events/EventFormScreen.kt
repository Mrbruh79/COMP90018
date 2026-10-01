package com.example.blap.ui.screens.events

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventVisibility
import com.example.blap.ui.screens.events.EVENT_DATE_FORMAT
import com.example.blap.ui.screens.events.EVENT_TIME_FORMAT
import com.example.blap.venue.VenueManager
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventFormScreen(
    existing: CommunityEvent?,
    loading: Boolean,
    onSubmit: (EventCreateRequest) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var venueName by remember(existing?.id) { mutableStateOf(existing?.venueName.orEmpty()) }
    var selectedLocation by remember(existing?.id) {
        mutableStateOf(existing?.let { OsmPoint(it.latitude, it.longitude) })
    }
    var locationError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember(existing?.id) { mutableStateOf(existing?.venueName.orEmpty()) }
    var searchResults by remember { mutableStateOf<List<OsmSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var radius by remember(existing?.id) { mutableFloatStateOf(existing?.radiusMetres?.toFloat() ?: 100f) }
    var privateEvent by remember(existing?.id) {
        mutableStateOf(existing?.visibility == EventVisibility.PRIVATE)
    }
    var protectedEvent by remember(existing?.id) {
        mutableStateOf(existing?.requiresSignIn == true)
    }
    var startsAt by remember(existing?.id) {
        mutableStateOf(existing?.startsAt?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()) }
            ?: defaultEventStart())
    }
    var endsAt by remember(existing?.id) {
        mutableStateOf(existing?.endsAt?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()) }
            ?: startsAt.plusHours(4))
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    fun loadCurrentLocation() {
        scope.launch {
            val location = runCatching { VenueManager.getFreshLocation(context) }.getOrNull()
            if (location == null) {
                locationError = "Current location was not available."
            } else {
                selectedLocation = OsmPoint(location.latitude, location.longitude)
                venueName = "Current location"
                searchResults = emptyList()
                locationError = null
            }
        }
    }

    fun searchLocations() {
        val query = searchQuery.trim()
        if (query.length < 3 || searching) return
        searching = true
        locationError = null
        scope.launch {
            runCatching { OsmPlaceSearch.search(query) }
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

    val scheduleValid = endsAt.isAfter(startsAt) && endsAt.toInstant().toEpochMilli() > System.currentTimeMillis()
    val valid = title.isNotBlank() && selectedLocation != null && venueName.isNotBlank() && scheduleValid

    if (showStartDatePicker) {
        EventDatePickerDialog(
            title = "Start date",
            current = startsAt,
            onDismiss = { showStartDatePicker = false },
            onSelected = { date ->
                val updated = ZonedDateTime.of(date, startsAt.toLocalTime(), startsAt.zone)
                startsAt = updated
                if (!endsAt.isAfter(updated)) endsAt = updated.plusHours(4)
                showStartDatePicker = false
            },
        )
    }
    if (showStartTimePicker) {
        EventTimePickerDialog(
            title = "Start time",
            current = startsAt,
            onDismiss = { showStartTimePicker = false },
            onSelected = { time ->
                val updated = ZonedDateTime.of(startsAt.toLocalDate(), time, startsAt.zone)
                startsAt = updated
                if (!endsAt.isAfter(updated)) endsAt = updated.plusHours(4)
                showStartTimePicker = false
            },
        )
    }
    if (showEndDatePicker) {
        EventDatePickerDialog(
            title = "End date",
            current = endsAt,
            onDismiss = { showEndDatePicker = false },
            onSelected = { date ->
                endsAt = ZonedDateTime.of(date, endsAt.toLocalTime(), endsAt.zone)
                showEndDatePicker = false
            },
        )
    }
    if (showEndTimePicker) {
        EventTimePickerDialog(
            title = "End time",
            current = endsAt,
            onDismiss = { showEndTimePicker = false },
            onSelected = { time ->
                endsAt = ZonedDateTime.of(endsAt.toLocalDate(), time, endsAt.zone)
                showEndTimePicker = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            Text(if (existing == null) "Create event" else "Edit event", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (existing == null) "You will become the primary admin."
                else "Changes sync to joined attendees online and through the on-site mesh.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(
                description,
                { description = it.take(1_000) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Private event", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (existing == null) {
                            "Hidden from Browse. Only signed-in CommonGround users you invite can join."
                        } else {
                            if (privateEvent) "Invite-only visibility" else "Visible to everyone"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = privateEvent,
                    onCheckedChange = {
                        privateEvent = it
                        if (it) protectedEvent = false
                    },
                    enabled = existing == null,
                )
            }
        }
        if (!privateEvent) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Protected public event", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Everyone can discover the event, but only signed-in users can join.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = protectedEvent,
                        onCheckedChange = { protectedEvent = it },
                    )
                }
            }
        }
        item {
            Text("Event location", style = MaterialTheme.typography.titleMedium)
            Text(
                "Search for a venue, use your current position, or tap the map to place the pin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(120) },
                    label = { Text("Venue or address") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = ::searchLocations, enabled = searchQuery.trim().length >= 3 && !searching) {
                    Text(if (searching) "Searching…" else "Search")
                }
            }
        }
        if (searchResults.isNotEmpty()) {
            items(searchResults, key = { "${it.point.latitude},${it.point.longitude}" }) { result ->
                Card(
                    onClick = {
                        selectedLocation = result.point
                        venueName = result.displayName
                        searchQuery = result.displayName
                        searchResults = emptyList()
                        locationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(result.displayName, modifier = Modifier.padding(12.dp), maxLines = 3)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use my location") }
            }
        }
        item {
            OsmEventMap(
                point = selectedLocation,
                radiusMetres = radius.toDouble(),
                onPointSelected = { point ->
                    selectedLocation = point
                    venueName = "Pinned location"
                    searchResults = emptyList()
                    locationError = null
                },
                height = 210.dp,
            )
        }
        if (venueName.isNotBlank()) {
            item {
                OutlinedTextField(
                    venueName,
                    { venueName = it.take(200) },
                    label = { Text("Venue or address") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        locationError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text("On-site radius: ${radius.toInt()} m")
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 20f..500f,
                steps = 23,
            )
        }
        item {
            Text("Event schedule", style = MaterialTheme.typography.titleMedium)
            Text(
                "Times use ${startsAt.zone.id}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Text("Starts", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.weight(1f),
                ) { Text(startsAt.format(EVENT_DATE_FORMAT)) }
                OutlinedButton(
                    onClick = { showStartTimePicker = true },
                    modifier = Modifier.weight(1f),
                ) { Text(startsAt.format(EVENT_TIME_FORMAT)) }
            }
        }
        item {
            Text("Ends", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.weight(1f),
                ) { Text(endsAt.format(EVENT_DATE_FORMAT)) }
                OutlinedButton(
                    onClick = { showEndTimePicker = true },
                    modifier = Modifier.weight(1f),
                ) { Text(endsAt.format(EVENT_TIME_FORMAT)) }
            }
        }
        if (!scheduleValid) {
            item {
                Text(
                    "The event must end after it starts and cannot already be over.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, enabled = !loading, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSubmit(
                            EventCreateRequest(
                                title,
                                description,
                                venueName,
                                requireNotNull(selectedLocation).latitude,
                                requireNotNull(selectedLocation).longitude,
                                radius.toDouble(),
                                startsAt.toInstant().toEpochMilli(),
                                endsAt.toInstant().toEpochMilli(),
                                if (privateEvent) EventVisibility.PRIVATE else EventVisibility.PUBLIC,
                                !privateEvent && protectedEvent,
                            ),
                        )
                    },
                    enabled = valid && !loading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (loading) {
                            if (existing == null) "Publishing…" else "Saving…"
                        } else if (existing == null) "Publish" else "Save changes",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDatePickerDialog(
    title: String,
    current: ZonedDateTime,
    onDismiss: () -> Unit,
    onSelected: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current.toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text(title, modifier = Modifier.padding(16.dp)) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventTimePickerDialog(
    title: String,
    current: ZonedDateTime,
    onDismiss: () -> Unit,
    onSelected: (LocalTime) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = current.hour,
        initialMinute = current.minute,
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onSelected(LocalTime.of(state.hour, state.minute)) }) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun defaultEventStart(): ZonedDateTime {
    val candidate = ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0)
    val minutesToQuarter = (15 - candidate.minute % 15) % 15
    return candidate.plusMinutes(minutesToQuarter.toLong())
}

private fun Throwable.toLocationSearchMessage(): String = when {
    generateSequence(this) { it.cause }.any { it is UnknownHostException } ->
        "No internet connection. Connect this phone to Wi-Fi or mobile data and try again."

    generateSequence(this) { it.cause }.any { it is SocketTimeoutException } ->
        "Location search timed out. Check the phone's internet connection and try again."

    else -> localizedMessage ?: "Location search failed."
}
