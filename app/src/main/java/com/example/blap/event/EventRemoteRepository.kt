package com.example.blap.event

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await

interface EventRemoteRepository {
    suspend fun requireUserId(): String
    suspend fun createEvent(event: CommunityEvent, creator: EventMembership)
    suspend fun listEvents(): List<CommunityEvent>
    suspend fun updateEvent(event: CommunityEvent)
    suspend fun deleteEvent(eventId: String)
    fun observeEvents(
        onEvents: (events: List<CommunityEvent>, authoritative: Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable? = null
    suspend fun joinEvent(membership: EventMembership)
    suspend fun leaveEvent(eventId: String, userId: String, leftAt: Long)
    suspend fun listMembers(eventId: String): List<EventMembership>
    suspend fun promoteToCoAdmin(eventId: String, userId: String)
    suspend fun blockMember(eventId: String, userId: String, blockedAt: Long)
    suspend fun registerAdminPublicKey(eventId: String, userId: String, encodedPublicKey: String)
    suspend fun saveAnnouncement(announcement: EventAnnouncement)
    suspend fun getAnnouncements(eventId: String, limit: Int = 100): List<EventAnnouncement>
}

class FirebaseEventRemoteRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : EventRemoteRepository {
    override suspend fun requireUserId(): String {
        auth.currentUser?.uid?.let { return it }
        auth.signInAnonymously().await()
        return requireNotNull(auth.currentUser?.uid) { "Firebase sign-in did not produce a user." }
    }

    override suspend fun createEvent(event: CommunityEvent, creator: EventMembership) {
        require(event.createdBy == creator.userId && creator.role == EventRole.PRIMARY_ADMIN)
        val eventRef = firestore.collection(EVENTS).document(event.id)
        firestore.runBatch { batch ->
            batch.set(eventRef, event.toRemoteMap())
            batch.set(eventRef.collection(MEMBERS).document(creator.userId), creator.toRemoteMap())
        }.await()
    }

    override suspend fun listEvents(): List<CommunityEvent> = firestore.collection(EVENTS)
        .orderBy("startsAt", Query.Direction.ASCENDING)
        .get()
        .await()
        .documents
        .mapNotNull { document -> document.data?.toEvent(document.id) }

    override suspend fun updateEvent(event: CommunityEvent) {
        firestore.collection(EVENTS).document(event.id).set(event.toRemoteMap()).await()
    }

    override suspend fun deleteEvent(eventId: String) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        deleteCollection(eventRef.collection(ANNOUNCEMENTS))
        deleteCollection(eventRef.collection(MEMBERS))
        eventRef.delete().await()
    }

    override fun observeEvents(
        onEvents: (events: List<CommunityEvent>, authoritative: Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        val registration = firestore.collection(EVENTS)
            .orderBy("startsAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                } else if (snapshot != null) {
                    onEvents(
                        snapshot.documents.mapNotNull { document -> document.data?.toEvent(document.id) },
                        !snapshot.metadata.isFromCache,
                    )
                }
            }
        return AutoCloseable(registration::remove)
    }

    private suspend fun deleteCollection(collection: com.google.firebase.firestore.CollectionReference) {
        while (true) {
            val documents = collection.limit(DELETE_BATCH_SIZE.toLong()).get().await().documents
            if (documents.isEmpty()) return
            val batch = firestore.batch()
            documents.forEach { document -> batch.delete(document.reference) }
            batch.commit().await()
        }
    }

    override suspend fun joinEvent(membership: EventMembership) {
        firestore.collection(EVENTS)
            .document(membership.eventId)
            .collection(MEMBERS)
            .document(membership.userId)
            .set(membership.toRemoteMap())
            .await()
    }

    override suspend fun leaveEvent(eventId: String, userId: String, leftAt: Long) {
        firestore.collection(EVENTS)
            .document(eventId)
            .collection(MEMBERS)
            .document(userId)
            .update("leftAt", leftAt)
            .await()
    }

    override suspend fun listMembers(eventId: String): List<EventMembership> = firestore
        .collection(EVENTS)
        .document(eventId)
        .collection(MEMBERS)
        .get()
        .await()
        .documents
        .mapNotNull { document -> document.data?.toMembership(eventId, document.id) }

    override suspend fun promoteToCoAdmin(eventId: String, userId: String) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        firestore.runBatch { batch ->
            batch.update(eventRef, "adminIds", FieldValue.arrayUnion(userId))
            batch.update(eventRef.collection(MEMBERS).document(userId), "role", EventRole.CO_ADMIN.name)
        }.await()
    }

    override suspend fun blockMember(eventId: String, userId: String, blockedAt: Long) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        firestore.runBatch { batch ->
            batch.update(eventRef, "adminIds", FieldValue.arrayRemove(userId))
            batch.update(eventRef.collection(MEMBERS).document(userId), "blockedAt", blockedAt)
        }.await()
    }

    override suspend fun registerAdminPublicKey(
        eventId: String,
        userId: String,
        encodedPublicKey: String,
    ) {
        firestore.collection(EVENTS)
            .document(eventId)
            .update("adminPublicKeys.$userId", encodedPublicKey)
            .await()
    }

    override suspend fun saveAnnouncement(announcement: EventAnnouncement) {
        firestore.collection(EVENTS)
            .document(announcement.eventId)
            .collection(ANNOUNCEMENTS)
            .document(announcement.id)
            .set(announcement.toRemoteMap())
            .await()
    }

    override suspend fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> =
        firestore.collection(EVENTS)
            .document(eventId)
            .collection(ANNOUNCEMENTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1, 500).toLong())
            .get()
            .await()
            .documents
            .mapNotNull { document -> document.data?.toAnnouncement(document.id, eventId) }
            .asReversed()

    private fun CommunityEvent.toRemoteMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "description" to description,
        "venueName" to venueName,
        "latitude" to latitude,
        "longitude" to longitude,
        "radiusMetres" to radiusMetres,
        "startsAt" to startsAt,
        "endsAt" to endsAt,
        "createdBy" to createdBy,
        "adminIds" to adminIds.toList(),
        "adminPublicKeys" to adminPublicKeys,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "deletedAt" to deletedAt,
    )

    private fun EventMembership.toRemoteMap(): Map<String, Any?> = mapOf(
        "displayName" to displayName,
        "role" to role.name,
        "joinedAt" to joinedAt,
        "blockedAt" to blockedAt,
        "leftAt" to leftAt,
        "accessMethod" to accessMethod?.name,
        "checkedInAt" to checkedInAt,
    )

    private fun EventAnnouncement.toRemoteMap(): Map<String, Any> = mapOf(
        "adminId" to adminId,
        "adminName" to adminName,
        "text" to text,
        "createdAt" to createdAt,
        "revision" to revision,
        "signature" to signature,
    )

    private fun Map<String, Any>.toEvent(id: String): CommunityEvent? = runCatching {
        val creator = getValue("createdBy") as String
        val publicKeys = (get("adminPublicKeys") as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }
            ?.toMap()
            ?: emptyMap()
        CommunityEvent(
            id = id,
            title = getValue("title") as String,
            description = get("description") as? String ?: "",
            venueName = get("venueName") as? String ?: "",
            latitude = (getValue("latitude") as Number).toDouble(),
            longitude = (getValue("longitude") as Number).toDouble(),
            radiusMetres = (getValue("radiusMetres") as Number).toDouble(),
            startsAt = (getValue("startsAt") as Number).toLong(),
            endsAt = (getValue("endsAt") as Number).toLong(),
            createdBy = creator,
            adminIds = (get("adminIds") as? List<*>)?.filterIsInstance<String>()?.toSet()
                ?.plus(creator) ?: setOf(creator),
            adminPublicKeys = publicKeys,
            createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
            updatedAt = (get("updatedAt") as? Number)?.toLong()
                ?: (get("createdAt") as? Number)?.toLong()
                ?: 0L,
            deletedAt = (get("deletedAt") as? Number)?.toLong(),
        )
    }.getOrNull()

    private fun Map<String, Any>.toAnnouncement(id: String, eventId: String): EventAnnouncement? =
        runCatching {
            EventAnnouncement(
                id = id,
                eventId = eventId,
                adminId = getValue("adminId") as String,
                adminName = getValue("adminName") as String,
                text = getValue("text") as String,
                createdAt = (getValue("createdAt") as Number).toLong(),
                revision = (getValue("revision") as Number).toLong(),
                signature = getValue("signature") as String,
                syncedToCloud = true,
            )
        }.getOrNull()

    private fun Map<String, Any>.toMembership(eventId: String, userId: String): EventMembership? =
        runCatching {
            EventMembership(
                eventId = eventId,
                userId = userId,
                displayName = get("displayName") as? String ?: "Event member",
                role = (get("role") as? String)?.let(EventRole::valueOf) ?: EventRole.ATTENDEE,
                joinedAt = (get("joinedAt") as? Number)?.toLong() ?: 0L,
                blockedAt = (get("blockedAt") as? Number)?.toLong(),
                leftAt = (get("leftAt") as? Number)?.toLong(),
                accessMethod = (get("accessMethod") as? String)?.let(EventAccessMethod::valueOf),
                checkedInAt = (get("checkedInAt") as? Number)?.toLong(),
            )
        }.getOrNull()

    private companion object {
        const val EVENTS = "events"
        const val MEMBERS = "members"
        const val ANNOUNCEMENTS = "announcements"
        const val DELETE_BATCH_SIZE = 450
    }
}
