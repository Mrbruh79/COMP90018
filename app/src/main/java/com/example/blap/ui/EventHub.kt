package com.example.blap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.MessageStatus
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventPage
import com.example.blap.event.EventUiState
import com.example.blap.venue.VenueManager
import java.text.SimpleDateFormat
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun EventHub(
    state: EventUiState,
    onBeginCreate: () -> Unit,
    onBeginEdit: () -> Unit,
    onCreate: (EventCreateRequest) -> Unit,
    onOpen: (String) -> Unit,
    onUpdate: (EventCreateRequest) -> Unit,
    onDeleteEvent: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onPromoteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteLocalData: () -> Unit,
    onShowAnnouncements: () -> Unit,
    onPublishAnnouncement: (String) -> Unit,
    onRequestGpsEntry: () -> Unit,
    onScanCheckInQr: () -> Unit,
    onShowCheckInQr: () -> Unit,
    onHideCheckInQr: () -> Unit,
    onShowSavedChat: () -> Unit,
    onSendChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    state.checkInQrPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = onHideCheckInQr,
            title = { Text("Venue check-in") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = createQrBitmap(payload, 700).asImageBitmap(),
                        contentDescription = "Rotating venue check-in QR",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "This signed code expires after five minutes.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onHideCheckInQr) { Text("Close") } },
        )
    }

    when (state.page) {
        EventPage.LIST -> EventListScreen(state, onBeginCreate, onOpen)
        EventPage.CREATE -> EventFormScreen(null, state.loading, onCreate, onBack)
        EventPage.EDIT -> EventFormScreen(state.selectedEvent, state.loading, onUpdate, onBack)
        EventPage.DETAIL -> EventDetailScreen(
            state,
            onBeginEdit,
            onDeleteEvent,
            onJoin,
            onLeave,
            onPromoteMember,
            onRemoveMember,
            onDeleteLocalData,
            onShowAnnouncements,
            onRequestGpsEntry,
            onScanCheckInQr,
            onShowCheckInQr,
            onShowSavedChat,
            onBack,
        )
        EventPage.ANNOUNCEMENTS -> EventAnnouncementsScreen(state, onPublishAnnouncement, onBack)
        EventPage.ON_SITE_CHAT -> EventChatScreen(state, onSendChat, onBack)
    }
}

@Composable
private fun EventListScreen(
    state: EventUiState,
    onBeginCreate: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Join online, connect on-site",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBeginCreate) { Text("Create") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (state.events.isEmpty()) {
                item {
                    Text(
                        if (state.loading) "Loading events…" else "No events available yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.events, key = CommunityEvent::id) { event ->
                Card(
                    onClick = { onOpen(event.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        if (event.isDeleted) {
                            Text(
                                "Deleted · read-only",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (event.description.isNotBlank()) {
                            Text(
                                event.description,
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(
                            listOfNotNull(
                                event.venueName.takeIf(String::isNotBlank),
                                "${formatEventTime(event.startsAt)} · ${event.radiusMetres.toInt()} m venue area",
                            ).joinToString("\n"),
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventFormScreen(
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

@Composable
private fun EventDetailScreen(
    state: EventUiState,
    onBeginEdit: () -> Unit,
    onDeleteEvent: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onPromoteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteLocalData: () -> Unit,
    onShowAnnouncements: () -> Unit,
    onRequestGpsEntry: () -> Unit,
    onScanCheckInQr: () -> Unit,
    onShowCheckInQr: () -> Unit,
    onShowSavedChat: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val event = state.selectedEvent ?: return
    val membership = state.membership
    val now = System.currentTimeMillis()
    var showDeleteConfirmation by remember(event.id) { mutableStateOf(false) }
    var showLeaveConfirmation by remember(event.id) { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete ${event.title}?") },
            text = {
                Text(
                    "This permanently removes the event, memberships, announcements and " +
                        "saved on-site chat from every reachable device. This cannot be undone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteEvent()
                    },
                    enabled = !state.loading,
                ) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Leave and delete ${event.title}?") },
            text = {
                Text(
                    "You are the primary admin. Leaving will permanently delete this event " +
                        "and all of its data for everyone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmation = false
                        onLeave()
                    },
                    enabled = !state.loading,
                ) { Text("Leave and delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item { TextButton(onClick = onBack) { Text("‹ All events") } }
        item {
            Text(event.title, style = MaterialTheme.typography.headlineMedium)
            if (event.isDeleted) {
                Text(
                    "Deleted by the event admin · saved content is read-only",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(event.description, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.venueName.isNotBlank()) {
                Text(
                    event.venueName,
                    modifier = Modifier.padding(top = 10.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "${formatEventTime(event.startsAt)} – ${formatEventTime(event.endsAt)}",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            EventVenueMap(event)
            OutlinedButton(
                onClick = { openEventInMaps(context, event) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Open in maps") }
        }
        if (event.isDeleted) {
            if (membership != null) {
                if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                    item {
                        Button(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Finish deleting event") }
                    }
                }
            }
        } else if (membership == null || membership.leftAt != null) {
            item {
                Button(onClick = onJoin, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("Join event")
                }
            }
            if (event.isActive(now)) {
                item {
                    OutlinedButton(onClick = onScanCheckInQr, modifier = Modifier.fillMaxWidth()) {
                        Text("Join and check in with venue QR")
                    }
                }
            }
        } else {
            if (membership.isAdmin) {
                item {
                    OutlinedButton(onClick = onBeginEdit, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit event")
                    }
                }
            }
            item { Button(onClick = onShowAnnouncements, modifier = Modifier.fillMaxWidth()) { Text("Announcements") } }
            if (event.isActive(now)) {
                item { Button(onClick = onRequestGpsEntry, modifier = Modifier.fillMaxWidth()) { Text("Enter on-site chat") } }
                item { OutlinedButton(onClick = onScanCheckInQr, modifier = Modifier.fillMaxWidth()) { Text("Check in with venue QR") } }
                if (membership.isAdmin) {
                    item { OutlinedButton(onClick = onShowCheckInQr, modifier = Modifier.fillMaxWidth()) { Text("Display venue check-in QR") } }
                }
            } else {
                item { OutlinedButton(onClick = onShowSavedChat, modifier = Modifier.fillMaxWidth()) { Text("View saved on-site chat") } }
            }
            item {
                OutlinedButton(
                    onClick = {
                        if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                            showLeaveConfirmation = true
                        } else {
                            onLeave()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) "Leave and delete event" else "Leave event") }
            }
            if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                item { Text("Event team", style = MaterialTheme.typography.titleMedium) }
                items(state.members.filter { it.canParticipate }, key = { "member-${it.userId}" }) { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(member.displayName)
                            Text(member.role.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.bodySmall)
                        }
                        if (
                            member.userId != membership.userId &&
                            member.role != com.example.blap.event.EventRole.PRIMARY_ADMIN
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (member.role == com.example.blap.event.EventRole.ATTENDEE) {
                                    TextButton(onClick = { onPromoteMember(member.userId) }) {
                                        Text("Make co-admin")
                                    }
                                }
                                TextButton(onClick = { onRemoveMember(member.userId) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
                }
            }
            item { TextButton(onClick = onDeleteLocalData, modifier = Modifier.fillMaxWidth()) { Text("Delete local event data") } }
        }
    }
}

@Composable
private fun EventAnnouncementsScreen(
    state: EventUiState,
    onPublish: (String) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(state.selectedEventId) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Event") }
        Text("Announcements", style = MaterialTheme.typography.headlineMedium)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (state.announcements.isEmpty()) item { Text("No announcements yet") }
            items(state.announcements, key = { it.id }) { announcement ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(announcement.adminName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(announcement.text, modifier = Modifier.padding(top = 5.dp))
                        Text(formatEventTime(announcement.createdAt), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.membership?.isAdmin == true && state.selectedEvent?.isDeleted == false) {
            MessageComposer(
                text = draft,
                onTextChanged = { draft = it },
                onSend = { onPublish(it); draft = "" },
            )
        } else {
            Text("Only event admins can post.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EventChatScreen(state: EventUiState, onSend: (String) -> Unit, onBack: () -> Unit) {
    var draft by remember(state.selectedEventId) { mutableStateOf("") }
    val membership = state.membership
    val writable = state.activeEventId == state.selectedEventId && state.selectedEvent?.isActive(System.currentTimeMillis()) == true
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Event") }
        Text("On-site chat", style = MaterialTheme.typography.headlineMedium)
        Text("Mesh only · not uploaded to the cloud", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (state.chatMessages.isEmpty()) item { Text("No on-site messages yet") }
            items(state.chatMessages.asReversed(), key = { it.id }) { message ->
                MessageBubble(
                    ChatMessage(
                        id = message.id,
                        peerId = message.eventId,
                        text = message.text,
                        author = if (message.senderId == membership?.userId) MessageAuthor.ME else MessageAuthor.PEER,
                        sentAt = message.createdAt,
                        status = MessageStatus.SENT,
                        senderId = message.senderId,
                        senderName = message.senderName,
                    ),
                    showSender = true,
                )
            }
        }
        if (writable) {
            MessageComposer(
                text = draft,
                onTextChanged = { draft = it },
                onSend = { onSend(it); draft = "" },
            )
        } else {
            Text("This saved chat is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EventVenueMap(event: CommunityEvent) {
    OsmEventMap(
        point = OsmPoint(event.latitude, event.longitude),
        radiusMetres = event.radiusMetres,
        height = 220.dp,
    )
}

private fun openEventInMaps(context: Context, event: CommunityEvent) {
    val label = Uri.encode(event.venueName.ifBlank { event.title })
    val geoUri = "geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}($label)".toUri()
    val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri)
    val intent = if (mapsIntent.resolveActivity(context.packageManager) != null) {
        mapsIntent
    } else {
        Intent(
            Intent.ACTION_VIEW,
            "https://www.openstreetmap.org/?mlat=${event.latitude}&mlon=${event.longitude}#map=17/${event.latitude}/${event.longitude}".toUri(),
        )
    }
    context.startActivity(intent)
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

private fun formatEventTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun defaultEventStart(): ZonedDateTime {
    val candidate = ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0)
    val minutesToQuarter = (15 - candidate.minute % 15) % 15
    return candidate.plusMinutes(minutesToQuarter.toLong())
}

private val EVENT_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM")
private val EVENT_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

private fun Throwable.toLocationSearchMessage(): String = when {
    generateSequence(this) { it.cause }.any { it is UnknownHostException } ->
        "No internet connection. Connect this phone to Wi-Fi or mobile data and try again."

    generateSequence(this) { it.cause }.any { it is SocketTimeoutException } ->
        "Location search timed out. Check the phone's internet connection and try again."

    else -> localizedMessage ?: "Location search failed."
}
