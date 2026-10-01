package com.example.blap.venue

import com.example.blap.location.LocationFix
import com.example.blap.location.LocationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VenueRepositoryTest {
    private val melbourneFix = LocationFix(
        latitude = -37.8136,
        longitude = 144.9631,
        accuracyMetres = 10.0,
        capturedAt = 1_000L,
    )

    @Test
    fun signedInUserReceivesFirstVenueWhoseRadiusContainsLocation() = runBlocking {
        val nearby = Venue("nearby", "Nearby venue", -37.8136, 144.9631, 50.0)
        val repository = FirebaseVenueRepository(
            locationProvider = FakeLocationProvider(melbourneFix),
            remoteDataSource = FakeVenueRemoteDataSource(
                listOf(
                    Venue("far", "Far venue", -37.9000, 145.1000, 20.0),
                    nearby,
                ),
            ),
            hasActiveUser = { true },
        )

        assertEquals(nearby, repository.findNearbyVenue())
    }

    @Test
    fun signedOutUserDoesNotRequestLocationOrFirestoreData() = runBlocking {
        val location = FakeLocationProvider(melbourneFix)
        val remote = FakeVenueRemoteDataSource(emptyList())
        val repository = FirebaseVenueRepository(location, remote) { false }

        assertNull(repository.findNearbyVenue())
        assertEquals(0, location.requests)
        assertEquals(0, remote.requests)
    }

    @Test
    fun missingLocationReturnsNoVenueWithoutCloudRead() = runBlocking {
        val remote = FakeVenueRemoteDataSource(emptyList())
        val repository = FirebaseVenueRepository(FakeLocationProvider(null), remote) { true }

        assertNull(repository.findNearbyVenue())
        assertEquals(0, remote.requests)
    }

    @Test
    fun invalidVenueCoordinatesAndRadiusAreIgnored() {
        val invalidCoordinates = Venue("bad-coordinates", "Bad", 200.0, 144.0, 100.0)
        val invalidRadius = Venue("bad-radius", "Bad", -37.8136, 144.9631, -1.0)

        assertNull(VenueMatcher.findNearby(melbourneFix, listOf(invalidCoordinates, invalidRadius)))
    }
}

private class FakeLocationProvider(private val fix: LocationFix?) : LocationProvider {
    var requests = 0

    override suspend fun getFreshLocation(): LocationFix? {
        requests += 1
        return fix
    }
}

private class FakeVenueRemoteDataSource(private val venues: List<Venue>) : VenueRemoteDataSource {
    var requests = 0

    override suspend fun listVenues(): List<Venue> {
        requests += 1
        return venues
    }
}
