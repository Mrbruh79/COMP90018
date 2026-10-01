package com.example.blap.venue

import com.example.blap.auth.AuthManager
import com.example.blap.location.GeoCoordinates
import com.example.blap.location.LocationDistanceCalculator
import com.example.blap.location.LocationFix
import com.example.blap.location.LocationProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class Venue(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
) {
    val coordinates: GeoCoordinates
        get() = GeoCoordinates(lat, lng)
}

interface VenueRepository {
    /** Caller obtains location permission first. Null means no matching venue or no active Firebase user. */
    suspend fun findNearbyVenue(): Venue?
}

interface VenueRemoteDataSource {
    suspend fun listVenues(): List<Venue>
}

class FirestoreVenueRemoteDataSource(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : VenueRemoteDataSource {
    override suspend fun listVenues(): List<Venue> = firestore.collection(VENUES)
        .get()
        .await()
        .documents
        .mapNotNull { document ->
            val latitude = document.getDouble("lat") ?: return@mapNotNull null
            val longitude = document.getDouble("lng") ?: return@mapNotNull null
            val radius = document.getDouble("radius") ?: return@mapNotNull null
            val name = document.getString("name") ?: return@mapNotNull null
            Venue(document.id, name, latitude, longitude, radius)
                .takeIf { it.coordinates.isValid && it.radius.isFinite() && it.radius > 0.0 }
        }

    private companion object {
        const val VENUES = "venues"
    }
}

class FirebaseVenueRepository(
    private val locationProvider: LocationProvider,
    private val remoteDataSource: VenueRemoteDataSource = FirestoreVenueRemoteDataSource(),
    private val hasActiveUser: () -> Boolean = { AuthManager.currentUserId != null },
) : VenueRepository {
    override suspend fun findNearbyVenue(): Venue? {
        if (!hasActiveUser()) return null
        val location = locationProvider.getFreshLocation() ?: return null
        return VenueMatcher.findNearby(location, remoteDataSource.listVenues())
    }
}

object VenueMatcher {
    /** Maintains the original first-match behaviour of the Firestore venue query. */
    fun findNearby(location: LocationFix, venues: List<Venue>): Venue? {
        if (!location.coordinates.isValid) return null
        return venues.firstOrNull { venue ->
            venue.coordinates.isValid && venue.radius.isFinite() && venue.radius > 0.0 &&
                LocationDistanceCalculator.distanceMetres(location.coordinates, venue.coordinates) <= venue.radius
        }
    }
}
