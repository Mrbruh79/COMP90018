package com.example.blap.ui.screens.events

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventAccessFilter
import com.example.blap.event.EventDateFilter
import com.example.blap.event.EventDiscoveryMode
import com.example.blap.event.EventDiscoveryPolicy
import com.example.blap.event.EventListSection
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.location.LocationFix
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun EventListScreen(
    state: EventUiState,
    onBeginCreate: () -> Unit,
    onSelectListSection: (EventListSection) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchEvents: () -> Unit,
    onClearEventSearch: () -> Unit,
    onDiscoverCity: (String) -> Unit,
    onDiscoverNearby: (LocationFix) -> Unit,
    onDiscoveryDistanceChanged: (Int) -> Unit,
    onDateFilterChanged: (EventDateFilter) -> Unit,
    onAccessFilterChanged: (EventAccessFilter) -> Unit,
    onLoadMoreEvents: () -> Unit,
    onOpen: (String) -> Unit,
    onAcceptInvitation: (String) -> Unit,
    onDeclineInvitation: (String) -> Unit,
    getCurrentLocation: suspend () -> LocationFix?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cityQuery by remember(state.currentUserId) { mutableStateOf(state.discoveryCityQuery) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var listClock by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.events, state.currentUserId) {
        while (true) {
            val now = System.currentTimeMillis()
            listClock = now
            val nextExpiry = state.events.asSequence()
                .filter { state.currentUserId in it.memberIds && it.endsAt >= now }
                .minOfOrNull(CommunityEvent::endsAt)
            val waitMillis = nextExpiry
                ?.let { end -> (end - now + 1).coerceIn(1_000L, 60_000L) }
                ?: 60_000L
            delay(waitMillis)
        }
    }

    val currentEvents = state.currentEventsForList(listClock)
    val pastEvents = state.pastJoinedEvents(listClock)

    fun loadNearby() {
        if (locating) return
        locating = true
        locationError = null
        scope.launch {
            runCatching { getCurrentLocation() }
                .onSuccess { fix ->
                    if (fix == null) {
                        locationError = "Your current location could not be found."
                    } else {
                        onDiscoverNearby(fix)
                    }
                    locating = false
                }
                .onFailure {
                    locationError = "Your current location could not be found."
                    locating = false
                }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) loadNearby()
        else locationError = "Allow location access to discover nearby events."
    }

    LaunchedEffect(state.currentUserId) {
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (
            locationGranted &&
            state.discoveryCentre == null &&
            state.discoveryMode == EventDiscoveryMode.UPCOMING
        ) {
            loadNearby()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Find an event or open one you joined",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBeginCreate) { Text("Create") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.eventListSection == EventListSection.DISCOVER,
                onClick = { onSelectListSection(EventListSection.DISCOVER) },
                label = { Text("Discover") },
            )
            FilterChip(
                selected = state.eventListSection == EventListSection.MY_EVENTS,
                onClick = { onSelectListSection(EventListSection.MY_EVENTS) },
                label = {
                    Text(
                        if (state.invitations.isEmpty()) "My events"
                        else "My events (${state.invitations.size})",
                    )
                },
            )
        }

        if (state.eventListSection == EventListSection.DISCOVER) {
            DiscoveryControls(
                state = state,
                cityQuery = cityQuery,
                locating = locating,
                onCityQueryChanged = { cityQuery = it.take(80) },
                onSearchQueryChanged = onSearchQueryChanged,
                onSearchEvents = onSearchEvents,
                onClearEventSearch = {
                    cityQuery = ""
                    onClearEventSearch()
                },
                onDiscoverCity = { onDiscoverCity(cityQuery) },
                onDiscoverNearby = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        loadNearby()
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onDiscoveryDistanceChanged = onDiscoveryDistanceChanged,
                onDateFilterChanged = onDateFilterChanged,
                onAccessFilterChanged = onAccessFilterChanged,
                locationError = locationError,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (state.showingOfflineEvents && state.eventListSection == EventListSection.MY_EVENTS) {
                item {
                    Text(
                        "Offline: showing joined events saved on this device.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (
                state.eventListSection == EventListSection.MY_EVENTS &&
                state.invitations.isNotEmpty()
            ) {
                item { Text("Private invitations", style = MaterialTheme.typography.titleLarge) }
                items(state.invitations, key = { "invite-${it.id}" }) { invitation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(invitation.eventTitle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Invited by ${invitation.inviterName} · ${formatEventTime(invitation.startsAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onAcceptInvitation(invitation.id) },
                                    enabled = !state.loading,
                                ) { Text("Accept") }
                                OutlinedButton(
                                    onClick = { onDeclineInvitation(invitation.id) },
                                    enabled = !state.loading,
                                ) { Text("Decline") }
                            }
                        }
                    }
                }
            }

            if (
                state.eventListSection == EventListSection.MY_EVENTS &&
                currentEvents.isNotEmpty()
            ) {
                item { Text("Active and upcoming", style = MaterialTheme.typography.titleLarge) }
            }

            if (currentEvents.isEmpty()) {
                item {
                    val message = when {
                        state.discoveryLoading && state.eventListSection == EventListSection.DISCOVER ->
                            "Finding events…"
                        state.loading && state.eventListSection == EventListSection.MY_EVENTS ->
                            "Loading your events…"
                        state.eventListSection == EventListSection.MY_EVENTS && pastEvents.isNotEmpty() ->
                            "You have no active or upcoming joined events."
                        state.eventListSection == EventListSection.MY_EVENTS ->
                            "You have not joined any events yet."
                        state.discoveryMode == EventDiscoveryMode.NEARBY ->
                            "No events found within ${state.discoveryDistanceKm} km."
                        state.discoveryMode == EventDiscoveryMode.CITY ->
                            "No events found for ${state.discoveryCityQuery}."
                        state.discoveryMode == EventDiscoveryMode.SEARCH ->
                            "No events match your search."
                        else -> "No upcoming public events found."
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items(currentEvents, key = CommunityEvent::id) { event ->
                EventDiscoveryCard(
                    event = event,
                    distanceMetres = if (
                        state.eventListSection == EventListSection.DISCOVER &&
                        state.discoveryMode == EventDiscoveryMode.NEARBY
                    ) {
                        state.discoveryCentre?.let { EventDiscoveryPolicy.distanceMetres(event, it) }
                    } else {
                        null
                    },
                    onOpen = { onOpen(event.id) },
                )
            }

            if (
                state.eventListSection == EventListSection.DISCOVER &&
                state.discoveryHasMore
            ) {
                item {
                    OutlinedButton(
                        onClick = onLoadMoreEvents,
                        enabled = !state.discoveryLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.discoveryLoading) "Loading…" else "Load more")
                    }
                }
            }

            if (pastEvents.isNotEmpty()) {
                item { Text("Past events", style = MaterialTheme.typography.titleLarge) }
                items(pastEvents, key = { "past-${it.id}" }) { event ->
                    EventDiscoveryCard(
                        event = event,
                        distanceMetres = null,
                        isPast = true,
                        onOpen = { onOpen(event.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryControls(
    state: EventUiState,
    cityQuery: String,
    locating: Boolean,
    onCityQueryChanged: (String) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchEvents: () -> Unit,
    onClearEventSearch: () -> Unit,
    onDiscoverCity: () -> Unit,
    onDiscoverNearby: () -> Unit,
    onDiscoveryDistanceChanged: (Int) -> Unit,
    onDateFilterChanged: (EventDateFilter) -> Unit,
    onAccessFilterChanged: (EventAccessFilter) -> Unit,
    locationError: String?,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.discoverySearchQuery,
                onValueChange = onSearchQueryChanged,
                label = { Text("Search events or venues") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSearchEvents,
                enabled = state.discoverySearchQuery.trim().length >= 2 && !state.discoveryLoading,
            ) { Text("Search") }
        }
        if (
            state.discoveryMode == EventDiscoveryMode.SEARCH ||
            state.discoveryMode == EventDiscoveryMode.CITY
        ) {
            OutlinedButton(onClick = onClearEventSearch) { Text("Clear discovery filter") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = cityQuery,
                onValueChange = onCityQueryChanged,
                label = { Text("City or suburb") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onDiscoverCity,
                enabled = cityQuery.trim().length >= 2 && !state.discoveryLoading,
            ) { Text("Apply") }
            OutlinedButton(onClick = onDiscoverNearby, enabled = !locating) {
                Text(if (locating) "Locating…" else "Near me")
            }
        }
        locationError?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.discoveryCentre != null) {
            Text("Nearby distance", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 25, 50).forEach { distance ->
                    FilterChip(
                        selected = state.discoveryDistanceKm == distance,
                        onClick = { onDiscoveryDistanceChanged(distance) },
                        label = { Text("$distance km") },
                    )
                }
            }
        }

        Text("When", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EventDateFilter.values().forEach { filter ->
                FilterChip(
                    selected = state.discoveryDateFilter == filter,
                    onClick = { onDateFilterChanged(filter) },
                    label = {
                        Text(
                            when (filter) {
                                EventDateFilter.ANY_UPCOMING -> "Upcoming"
                                EventDateFilter.TODAY -> "Today"
                                EventDateFilter.NEXT_7_DAYS -> "Next 7 days"
                            },
                        )
                    },
                )
            }
        }

        Text("Access", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EventAccessFilter.values().forEach { filter ->
                FilterChip(
                    selected = state.discoveryAccessFilter == filter,
                    onClick = { onAccessFilterChanged(filter) },
                    label = {
                        Text(
                            when (filter) {
                                EventAccessFilter.ALL -> "All public"
                                EventAccessFilter.OPEN -> "Open to guests"
                                EventAccessFilter.SIGN_IN_REQUIRED -> "Sign-in required"
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun EventDiscoveryCard(
    event: CommunityEvent,
    distanceMetres: Double?,
    isPast: Boolean = false,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            if (isPast) {
                Text(
                    "Past event · ended ${formatEventTime(event.endsAt)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            when {
                event.visibility == EventVisibility.PRIVATE -> Text(
                    "Private · invite only",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                event.requiresSignIn -> Text(
                    "Protected · sign-in required to join",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
                    formatEventTime(event.startsAt),
                    distanceMetres?.let(::formatDistance),
                ).joinToString("\n"),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatDistance(distanceMetres: Double): String = if (distanceMetres < 1_000) {
    "${distanceMetres.toInt()} m away"
} else {
    String.format(Locale.getDefault(), "%.1f km away", distanceMetres / 1_000.0)
}
