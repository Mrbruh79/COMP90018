package com.example.blap.venue

import android.annotation.SuppressLint
import android.content.Context
import com.example.blap.auth.AuthManager
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
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
    private suspend fun getFreshLocation(context: Context) =
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(
                CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).build(),
                null,
            )
            .await()

    // ------------ GPS CHECK-IN FUNCTIONS ------------ //
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
                sin(dLng / 2) * sin(dLng / 2)                                              // used haversine formula to calculates real-world distance between two lat/lng points.
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // ------------ THREAD FUNCTIONS ------------ //

    suspend fun postThread(venueId: String, title: String): Boolean {
        val authorId = AuthManager.currentUserId ?: return false
        if (title.isBlank()) return false

        val thread = hashMapOf(
            "title" to title,
            "authorId" to authorId,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyCount" to 0L,
        )

        return try {
            firestore.collection("venues")
                .document(venueId)
                .collection("threads")
                .add(thread)
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    enum class ThreadSort { NEWEST, OLDEST, MOST_REPLIES }

    fun listenToThreads(
        venueId: String,
        sort: ThreadSort = ThreadSort.NEWEST,
        onUpdate: (List<DiscussionThread>) -> Unit,
    ): ListenerRegistration {
        val threadsRef = firestore.collection("venues").document(venueId).collection("threads")
        val query = when (sort) {
            ThreadSort.NEWEST -> threadsRef.orderBy("createdAt", Query.Direction.DESCENDING)
            ThreadSort.OLDEST -> threadsRef.orderBy("createdAt", Query.Direction.ASCENDING)
            ThreadSort.MOST_REPLIES -> threadsRef.orderBy("replyCount", Query.Direction.DESCENDING)
        }

        return query.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                onUpdate(emptyList())
                return@addSnapshotListener
            }
            val threads = snapshot.documents.mapNotNull { doc ->
                doc.toObject(DiscussionThread::class.java)?.copy(id = doc.id)
            }
            onUpdate(threads)
        }
    }

    suspend fun postReply(venueId: String, threadId: String, text: String): Boolean {
        val authorId = AuthManager.currentUserId ?: return false
        if (text.isBlank()) return false

        val threadRef = firestore.collection("venues")
            .document(venueId)
            .collection("threads")
            .document(threadId)

        val replyRef = threadRef.collection("replies").document()

        val reply = hashMapOf(
            "text" to text,
            "authorId" to authorId,
            "createdAt" to FieldValue.serverTimestamp(),
        )

        return try {
            firestore.runBatch { batch ->
                batch.set(replyRef, reply)
                batch.update(threadRef, "replyCount", FieldValue.increment(1))
            }.await()
            true
        } catch (e: Exception) {
            false
        }
    }
    suspend fun toggleUpvote(venueId: String, threadId: String): Boolean {
        val userId = AuthManager.currentUserId ?: return false

        val threadRef = firestore.collection("venues")
            .document(venueId)
            .collection("threads")
            .document(threadId)

        val voteRef = threadRef.collection("votes").document(userId)

        return try {
            firestore.runTransaction { transaction ->
                val voteDoc = transaction.get(voteRef)
                if (voteDoc.exists()) {
                    transaction.delete(voteRef)
                    transaction.update(threadRef, "upvoteCount", FieldValue.increment(-1))
                } else {
                    transaction.set(voteRef, mapOf("votedAt" to FieldValue.serverTimestamp()))
                    transaction.update(threadRef, "upvoteCount", FieldValue.increment(1))
                }
            }.await()
            true
        } catch (e: Exception) {
            false
        }
    }
}