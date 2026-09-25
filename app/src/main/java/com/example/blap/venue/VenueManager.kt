package com.example.blap.venue

import android.annotation.SuppressLint
import android.content.Context
import com.example.blap.auth.AuthManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Venue(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
)

object VenueManager {
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    @SuppressLint("MissingPermission")
    suspend fun getFreshLocation(context: Context) =
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).build(),
                null,
            )
            .await()

    suspend fun findNearbyVenue(context: Context): Venue? {
        if (AuthManager.currentUserId == null) return null

        val location = getFreshLocation(context) ?: return null

        val venues = firestore.collection("venues")
            .get()
            .await()
            .documents
            .mapNotNull { doc ->
                val lat = doc.getDouble("lat") ?: return@mapNotNull null
                val lng = doc.getDouble("lng") ?: return@mapNotNull null
                val radius = doc.getDouble("radius") ?: return@mapNotNull null
                val name = doc.getString("name") ?: return@mapNotNull null
                Venue(doc.id, name, lat, lng, radius)
            }

        return venues.firstOrNull { venue ->
            distanceMeters(location.latitude, location.longitude, venue.lat, venue.lng) <= venue.radius
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
