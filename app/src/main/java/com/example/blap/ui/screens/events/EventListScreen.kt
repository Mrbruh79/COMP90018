package com.example.blap.ui.screens.events

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.blap.event.DEFAULT_DISCOVERY_DISTANCE_KM
import com.example.blap.event.EventDiscoveryMode
import com.example.blap.event.EventDiscoveryPolicy
import com.example.blap.event.EventListSection
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.location.LocationFix
import com.example.blap.ui.theme.ButtonHeightExtraSmall
import com.example.blap.ui.theme.ButtonHeightMedium
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState()
    val filtersActive = state.discoveryMode == EventDiscoveryMode.CITY ||
        state.discoveryMode == EventDiscoveryMode.SEARCH ||
        state.discoveryDistanceKm != DEFAULT_DISCOVERY_DISTANCE_KM ||
        state.discoveryDateFilter != EventDateFilter.ANY_UPCOMING ||
        state.discoveryAccessFilter != EventAccessFilter.ALL

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
                    colors = sectionChipColors(),
                )
                FilterChip(
                    selected = state.eventListSection == EventListSection.MY_EVENTS,
                    onClick = { onSelectListSection(EventListSection.MY_EVENTS) },
                    colors = sectionChipColors(),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.discoverySearchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = { Text("Search events") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onSearchEvents,
                    enabled = state.discoverySearchQuery.trim().length >= 2 && !state.discoveryLoading,
                ) { Text("Search") }
            }
            FilterChip(
                selected = filtersActive,
                onClick = { showFilters = true },
                label = { Text(if (filtersActive) "Filter Active" else "Filter Events") },
                leadingIcon = {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (showFilters) {
            EventFilterSheet(
                state = state,
                cityQuery = cityQuery,
                locating = locating,
                locationError = locationError,
                filtersActive = filtersActive,
                sheetState = filterSheetState,
                onCityQueryChanged = { cityQuery = it.take(80) },
                onClearFilters = {
                    cityQuery = ""
                    onClearEventSearch()
                    onDiscoveryDistanceChanged(DEFAULT_DISCOVERY_DISTANCE_KM)
                    onDateFilterChanged(EventDateFilter.ANY_UPCOMING)
                    onAccessFilterChanged(EventAccessFilter.ALL)
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
                onDismiss = { showFilters = false },
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

/** Outlined buttons carry the accent border and label used elsewhere in the app, dimming together when disabled. */
@Composable
private fun accentOutlineBorder(enabled: Boolean) = BorderStroke(
    1.dp,
    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun accentOutlineColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.primary,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** The section tabs switch which collection is shown, so they carry more emphasis than the filters below. */
@Composable
private fun sectionChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventFilterSheet(
    state: EventUiState,
    cityQuery: String,
    locating: Boolean,
    locationError: String?,
    filtersActive: Boolean,
    sheetState: SheetState,
    onCityQueryChanged: (String) -> Unit,
    onClearFilters: () -> Unit,
    onDiscoverCity: () -> Unit,
    onDiscoverNearby: () -> Unit,
    onDiscoveryDistanceChanged: (Int) -> Unit,
    onDateFilterChanged: (EventDateFilter) -> Unit,
    onAccessFilterChanged: (EventAccessFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filter Events", style = MaterialTheme.typography.titleMedium)
                if (filtersActive) {
                    OutlinedButton(
                        onClick = onClearFilters,
                        modifier = Modifier.height(ButtonHeightExtraSmall),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        border = accentOutlineBorder(true),
                        colors = accentOutlineColors(),
                    ) { Text("Clear Filter") }
                }
            }
            HorizontalDivider()

            Text("Location", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = cityQuery,
                    onValueChange = onCityQueryChanged,
                    placeholder = { Text("Search cities or suburbs") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                val citySearchEnabled = cityQuery.trim().length >= 2 && !state.discoveryLoading
                OutlinedButton(
                    onClick = onDiscoverCity,
                    enabled = citySearchEnabled,
                    border = accentOutlineBorder(citySearchEnabled),
                    colors = accentOutlineColors(),
                ) { Text("Search") }
            }
            OutlinedButton(
                onClick = onDiscoverNearby,
                enabled = !locating,
                modifier = Modifier.fillMaxWidth(),
                border = accentOutlineBorder(!locating),
                colors = accentOutlineColors(),
            ) {
                Icon(
                    painterResource(R.drawable.ic_location),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    when {
                        locating -> "Locating…"
                        state.discoveryCentre == null -> "Use current location"
                        else -> "Refresh Location"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            locationError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.discoveryCentre != null) {
                HorizontalDivider()
                Text("Search Radius", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 10, 25, 50).forEach { distance ->
                        FilterChip(
                            selected = state.discoveryDistanceKm == distance,
                            onClick = { onDiscoveryDistanceChanged(distance) },
                            label = { Text("${distance}km") },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Event Start Time", style = MaterialTheme.typography.bodyMedium)
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
                                    EventDateFilter.NEXT_7_DAYS -> "Next 7 Days"
                                },
                            )
                        },
                    )
                }
            }

            HorizontalDivider()
            Text("Access Requirements", style = MaterialTheme.typography.bodyMedium)
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

            HorizontalDivider()
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonHeightMedium),
            ) { Text("Filter Events") }
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
            EventIconRow(R.drawable.ic_calendar, formatEventTime(event.startsAt))
            event.venueName.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(8.dp))
                EventIconRow(R.drawable.ic_location, it)
            }
            distanceMetres?.let {
                Spacer(Modifier.height(8.dp))
                EventIconRow(R.drawable.ic_location, formatDistance(it))
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

private fun formatDistance(distanceMetres: Double): String = if (distanceMetres < 1_000) {
    "${distanceMetres.toInt()} m away"
} else {
    String.format(Locale.getDefault(), "%.1f km away", distanceMetres / 1_000.0)
}
