package com.example.blap.ui.screens.events

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blap.R
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventAccessFilter
import com.example.blap.event.EventDateFilter
import com.example.blap.event.EventDiscoveryMode
import com.example.blap.event.EventDiscoveryPolicy
import com.example.blap.event.EventListSection
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.location.LocationFix
import com.example.blap.ui.theme.ButtonHeightExtraSmall
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.eventListSection == EventListSection.DISCOVER,
                    onClick = { onSelectListSection(EventListSection.DISCOVER) },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = state.eventListSection == EventListSection.MY_EVENTS,
                    onClick = { onSelectListSection(EventListSection.MY_EVENTS) },
                    label = {
                        Text(
                            if (state.invitations.isEmpty()) "My Events"
                            else "My Events(${state.invitations.size})",
                        )
                    },
                )
            }
            Button(
                onClick = onBeginCreate,
                modifier = Modifier.height(ButtonHeightExtraSmall),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("Create Event", modifier = Modifier.padding(start = 6.dp))
            }
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
                item { Text("Private invitations", style = MaterialTheme.typography.titleMedium) }
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
                item { Text("Active and Upcoming", style = MaterialTheme.typography.titleMedium) }
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
                            "No results found. Please try again."
                        else -> "No events scheduled near you."
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
                    now = listClock,
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
                item { Text("Past Event", style = MaterialTheme.typography.titleMedium) }
                items(pastEvents, key = { "past-${it.id}" }) { event ->
                    EventDiscoveryCard(
                        event = event,
                        distanceMetres = null,
                        now = listClock,
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
                Text(
                    when {
                        locating -> "Locating…"
                        state.discoveryCentre == null -> "Use current location"
                        else -> "Refresh location"
                    },
                )
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
    now: Long,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_calendar),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (event.description.isNotBlank()) {
                        Text(
                            event.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            eventStatusLabel(event, now)?.let { status ->
                Text(
                    status,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            EventCardDetail(R.drawable.ic_calendar, formatEventTime(event.startsAt))
            event.venueName.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(8.dp))
                EventCardDetail(R.drawable.ic_location, it)
            }
            distanceMetres?.let {
                Spacer(Modifier.height(8.dp))
                EventCardDetail(R.drawable.ic_location, formatDistance(it))
            }
        }
    }
}

private fun eventStatusLabel(event: CommunityEvent, now: Long): String? = when {
    event.isDeleted -> "Deleted Event - Read Only"
    event.endsAt < now -> "Event ended on ${formatEventTime(event.endsAt)}"
    event.visibility == EventVisibility.PRIVATE -> "Private Event - Invite Only"
    event.requiresSignIn -> "Protected Event - Signed-in Users Only"
    else -> null
}

@Composable
private fun EventCardDetail(@DrawableRes iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatDistance(distanceMetres: Double): String = if (distanceMetres < 1_000) {
    "${distanceMetres.toInt()} m away"
} else {
    String.format(Locale.getDefault(), "%.1f km away", distanceMetres / 1_000.0)
}
