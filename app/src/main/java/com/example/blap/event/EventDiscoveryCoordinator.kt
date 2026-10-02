package com.example.blap.event

import com.example.blap.location.GeoCoordinates
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns bounded public-event discovery independently from joined-event synchronization. */
internal class EventDiscoveryCoordinator(
    private val remoteRepository: EventRemoteRepository,
    private val scope: CoroutineScope,
    private val currentState: () -> EventUiState,
    private val updateState: (((EventUiState) -> EventUiState) -> Unit),
    private val clock: () -> Long,
) {
    private val requestVersion = AtomicLong()

    fun selectSection(section: EventListSection) {
        updateState { it.copy(eventListSection = section, error = null) }
        if (section == EventListSection.DISCOVER) refresh()
    }

    fun updateSearchQuery(query: String) {
        updateState { it.copy(discoverySearchQuery = query.take(MAX_SEARCH_QUERY_LENGTH)) }
    }

    fun search() {
        val query = currentState().discoverySearchQuery.trim()
        if (query.length < MIN_FILTER_QUERY_LENGTH) {
            updateState { it.copy(error = "Enter at least two characters to search events.") }
            return
        }
        updateState {
            it.copy(
                eventListSection = EventListSection.DISCOVER,
                discoveryMode = EventDiscoveryMode.SEARCH,
                discoveryLimit = DISCOVERY_PAGE_SIZE,
                error = null,
            )
        }
        refresh()
    }

    fun clearSearch() {
        updateState { state ->
            state.copy(
                discoverySearchQuery = "",
                discoveryCityQuery = "",
                discoveryMode = if (state.discoveryCentre != null) {
                    EventDiscoveryMode.NEARBY
                } else {
                    EventDiscoveryMode.UPCOMING
                },
                discoveryLimit = DISCOVERY_PAGE_SIZE,
                error = null,
            )
        }
        refresh()
    }

    fun discoverCity(city: String) {
        val clean = city.trim().take(MAX_CITY_QUERY_LENGTH)
        if (clean.length < MIN_FILTER_QUERY_LENGTH) {
            updateState { it.copy(error = "Enter at least two characters for the city.") }
            return
        }
        updateState {
            it.copy(
                eventListSection = EventListSection.DISCOVER,
                discoveryMode = EventDiscoveryMode.CITY,
                discoveryCityQuery = clean,
                discoverySearchQuery = "",
                discoveryLimit = DISCOVERY_PAGE_SIZE,
                error = null,
            )
        }
        refresh()
    }

    fun discoverNearby(coordinates: GeoCoordinates) {
        if (!coordinates.isValid) {
            updateState { it.copy(error = "A valid current location is required for nearby events.") }
            return
        }
        updateState {
            it.copy(
                eventListSection = EventListSection.DISCOVER,
                discoveryMode = EventDiscoveryMode.NEARBY,
                discoveryCentre = coordinates,
                discoverySearchQuery = "",
                discoveryLimit = DISCOVERY_PAGE_SIZE,
                error = null,
            )
        }
        refresh()
    }

    fun setDistance(distanceKm: Int) {
        updateState {
            it.copy(
                discoveryDistanceKm = distanceKm.coerceIn(5, 50),
                discoveryLimit = DISCOVERY_PAGE_SIZE,
            )
        }
        if (currentState().discoveryCentre != null) {
            updateState { it.copy(discoveryMode = EventDiscoveryMode.NEARBY) }
            refresh()
        }
    }

    fun setDateFilter(filter: EventDateFilter) {
        updateState { it.copy(discoveryDateFilter = filter, discoveryLimit = DISCOVERY_PAGE_SIZE) }
        refresh()
    }

    fun setAccessFilter(filter: EventAccessFilter) {
        updateState { it.copy(discoveryAccessFilter = filter, discoveryLimit = DISCOVERY_PAGE_SIZE) }
        refresh()
    }

    fun loadMore() {
        val state = currentState()
        if (state.discoveryLoading || !state.discoveryHasMore) return
        updateState {
            it.copy(discoveryLimit = (it.discoveryLimit + DISCOVERY_PAGE_SIZE).coerceAtMost(MAX_DISCOVERY_RESULTS))
        }
        refresh()
    }

    fun refresh() {
        val state = currentState()
        if (state.eventListSection != EventListSection.DISCOVER) return
        val request = EventDiscoveryRequest(
            mode = state.discoveryMode,
            searchQuery = state.discoverySearchQuery,
            cityQuery = state.discoveryCityQuery,
            centre = state.discoveryCentre,
            distanceKm = state.discoveryDistanceKm,
            dateFilter = state.discoveryDateFilter,
            accessFilter = state.discoveryAccessFilter,
            limit = state.discoveryLimit,
            now = clock(),
        )
        val version = requestVersion.incrementAndGet()
        updateState { it.copy(discoveryLoading = true, error = null) }
        scope.launch {
            runCatching { remoteRepository.discoverEvents(request) }
                .onSuccess { result ->
                    if (requestVersion.get() != version) return@onSuccess
                    updateState { current ->
                        val retained = current.events.filter { event ->
                            current.currentUserId in event.memberIds || event.id == current.selectedEventId
                        }
                        current.copy(
                            events = (retained + result.events)
                                .distinctBy(CommunityEvent::id)
                                .sortedBy(CommunityEvent::startsAt),
                            discoveryEventIds = result.events.map(CommunityEvent::id),
                            discoveryLoading = false,
                            discoveryHasMore = result.hasMore && current.discoveryLimit < MAX_DISCOVERY_RESULTS,
                        )
                    }
                }
                .onFailure { failure ->
                    if (requestVersion.get() != version) return@onFailure
                    updateState {
                        it.copy(
                            discoveryLoading = false,
                            error = failure.readableEventMessage("Events could not be discovered"),
                        )
                    }
                }
        }
    }

    fun resetForAccount() {
        requestVersion.incrementAndGet()
        updateState {
            it.copy(
                discoveryEventIds = emptyList(),
                discoverySearchQuery = "",
                discoveryCityQuery = "",
                discoveryCentre = null,
                discoveryMode = EventDiscoveryMode.UPCOMING,
                discoveryLoading = false,
                discoveryHasMore = false,
                discoveryLimit = DISCOVERY_PAGE_SIZE,
            )
        }
    }

    private companion object {
        const val MIN_FILTER_QUERY_LENGTH = 2
        const val MAX_SEARCH_QUERY_LENGTH = 80
        const val MAX_CITY_QUERY_LENGTH = 80
    }
}
