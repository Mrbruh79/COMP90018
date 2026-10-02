package com.example.blap.event

import com.example.blap.location.GeoCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiscoveryPolicyTest {
    private val now = 1_800_000_000_000L
    private val melbourne = GeoCoordinates(-37.8136, 144.9631)

    @Test
    fun publicDiscoveryExcludesPrivateDeletedAndEndedEvents() {
        val visible = event("visible")
        val results = EventDiscoveryPolicy.filterAndSort(
            listOf(
                visible,
                event("private", visibility = EventVisibility.PRIVATE),
                event("deleted").copy(deletedAt = now),
                event("ended").copy(startsAt = now - 2_000, endsAt = now - 1),
            ),
            EventDiscoveryRequest(now = now),
        )

        assertEquals(listOf(visible), results)
    }

    @Test
    fun searchMatchesNormalisedTitleAndVenueTerms() {
        val cafe = event("cafe", title = "Café Connect", venue = "Melbourne Town Hall")
        val other = event("other", title = "Park Run", venue = "Carlton")

        val results = EventDiscoveryPolicy.filterAndSort(
            listOf(cafe, other),
            EventDiscoveryRequest(
                mode = EventDiscoveryMode.SEARCH,
                searchQuery = "cafe melbourne",
                now = now,
            ),
        )

        assertEquals(listOf(cafe), results)
        assertTrue(EventSearchIndex.tokensFor(cafe).contains("cafe"))
        assertEquals("cafe", EventSearchIndex.lookupToken("  Café networking "))
    }

    @Test
    fun nearbyResultsUseExactDistanceAndNearestFirst() {
        val nearest = event("nearest", latitude = melbourne.latitude, longitude = melbourne.longitude)
        val nearby = event("nearby", latitude = -37.82, longitude = 144.97)
        val distant = event("distant", latitude = -38.50, longitude = 145.70)

        val results = EventDiscoveryPolicy.filterAndSort(
            listOf(distant, nearby, nearest),
            EventDiscoveryRequest(
                mode = EventDiscoveryMode.NEARBY,
                centre = melbourne,
                distanceKm = 10,
                now = now,
            ),
        )

        assertEquals(listOf("nearest", "nearby"), results.map(CommunityEvent::id))
    }

    @Test
    fun cityDateAndAccessFiltersCombine() {
        val openToday = event("open", venue = "Melbourne CBD", startsAt = now + 60_000)
        val protectedToday = event("protected", venue = "Melbourne Museum", startsAt = now + 120_000)
            .copy(requiresSignIn = true)
        val otherCity = event("geelong", venue = "Geelong Waterfront", startsAt = now + 60_000)

        val results = EventDiscoveryPolicy.filterAndSort(
            listOf(protectedToday, otherCity, openToday),
            EventDiscoveryRequest(
                mode = EventDiscoveryMode.CITY,
                cityQuery = "Melbourne",
                accessFilter = EventAccessFilter.OPEN,
                now = now,
            ),
        )

        assertEquals(listOf(openToday), results)
    }

    @Test
    fun geohashCoverIncludesTheCentrePrefix() {
        val hash = EventGeoHash.encode(melbourne)
        val prefixes = EventGeoHash.coveringPrefixes(melbourne, 25)

        assertEquals(8, hash.length)
        assertFalse(prefixes.isEmpty())
        assertTrue(prefixes.any(hash::startsWith))
    }

    @Test
    fun returningFromAnEventPreservesDiscoveryState() {
        val discovered = event("discovered")
        val state = EventUiState(
            page = EventPage.DETAIL,
            events = listOf(discovered),
            selectedEventId = discovered.id,
            eventListSection = EventListSection.DISCOVER,
            discoveryMode = EventDiscoveryMode.CITY,
            discoveryEventIds = listOf(discovered.id),
            discoveryCityQuery = "Melbourne",
            currentUserId = "member",
        )

        val returned = state.returnToEventList(notice = "Done")

        assertEquals(EventPage.LIST, returned.page)
        assertEquals(EventDiscoveryMode.CITY, returned.discoveryMode)
        assertEquals(listOf(discovered.id), returned.discoveryEventIds)
        assertEquals(listOf(discovered), returned.listedEvents)
        assertEquals(null, returned.selectedEventId)
    }

    @Test
    fun pastAreaContainsOnlyEventsTheCurrentUserJoined() {
        val upcomingJoined = event("upcoming").copy(memberIds = setOf("admin", "member"))
        val pastJoined = event("past-joined").copy(
            startsAt = now - 10_000,
            endsAt = now - 1,
            memberIds = setOf("admin", "member"),
        )
        val pastUnjoined = event("past-unjoined").copy(
            startsAt = now - 10_000,
            endsAt = now - 1,
            memberIds = setOf("admin"),
        )
        val state = EventUiState(
            events = listOf(pastUnjoined, pastJoined, upcomingJoined),
            eventListSection = EventListSection.MY_EVENTS,
            currentUserId = "member",
        )

        assertEquals(listOf(upcomingJoined), state.currentEventsForList(now))
        assertEquals(listOf(pastJoined), state.pastJoinedEvents(now))
    }

    @Test
    fun discoverNeverShowsExpiredEventsOrAJoinedPastArea() {
        val upcoming = event("upcoming")
        val expired = event("expired").copy(startsAt = now - 10_000, endsAt = now - 1)
        val state = EventUiState(
            events = listOf(expired, upcoming),
            eventListSection = EventListSection.DISCOVER,
            discoveryEventIds = listOf(expired.id, upcoming.id),
            currentUserId = "member",
        )

        assertEquals(listOf(upcoming), state.currentEventsForList(now))
        assertTrue(state.pastJoinedEvents(now).isEmpty())
    }

    private fun event(
        id: String,
        title: String = "Community event",
        venue: String = "Melbourne",
        latitude: Double = melbourne.latitude,
        longitude: Double = melbourne.longitude,
        startsAt: Long = now + 60_000,
        visibility: EventVisibility = EventVisibility.PUBLIC,
    ) = CommunityEvent(
        id = id,
        title = title,
        description = "Description",
        venueName = venue,
        latitude = latitude,
        longitude = longitude,
        startsAt = startsAt,
        endsAt = startsAt + 3_600_000,
        createdBy = "admin",
        visibility = visibility,
        privateMeshSecret = if (visibility == EventVisibility.PRIVATE) "private-secret" else "",
    )
}
