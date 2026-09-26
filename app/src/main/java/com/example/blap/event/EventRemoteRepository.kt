package com.example.blap.event

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Locale
import kotlinx.coroutines.tasks.await

interface EventRemoteRepository {
    suspend fun requireUserId(): String
    fun hasSignedInAccount(): Boolean
    suspend fun createEvent(event: CommunityEvent, creator: EventMembership)
    suspend fun listEvents(): List<CommunityEvent>
    suspend fun updateEvent(event: CommunityEvent)
    suspend fun deleteEvent(eventId: String)
    fun observeEvents(
        onEvents: (events: List<CommunityEvent>, authoritative: Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable? = null
    fun observeInvitations(
        onInvitations: (List<EventInvitation>) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable? = null
    suspend fun listInvitations(): List<EventInvitation>
    suspend fun listEventInvitations(eventId: String): List<EventInvitation>
    suspend fun findInvitee(identifier: String): EventInvitee?
    suspend fun invite(invitation: EventInvitation)
    suspend fun acceptInvitation(invitation: EventInvitation, membership: EventMembership)
    suspend fun declineInvitation(invitation: EventInvitation)
    suspend fun revokeInvitation(invitation: EventInvitation)
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
    override fun hasSignedInAccount(): Boolean = auth.currentUser
        ?.takeUnless { it.isAnonymous }
        ?.email
        ?.isNotBlank() == true

    override suspend fun requireUserId(): String {
        auth.currentUser?.uid?.let { return it }
        auth.signInAnonymously().await()
        return requireNotNull(auth.currentUser?.uid) { "Firebase sign-in did not produce a user." }
    }

    private fun requireAccountUserId(): String = auth.currentUser
        ?.takeUnless { it.isAnonymous }
        ?.uid
        ?: throw IllegalStateException("Sign in with Email or Google to use private events.")

    override suspend fun createEvent(event: CommunityEvent, creator: EventMembership) {
        require(requireUserId() == creator.userId) {
            "Your sign-in changed while creating the event. Please try again."
        }
        if (event.visibility == EventVisibility.PRIVATE || event.requiresSignIn) requireAccountUserId()
        require(event.createdBy == creator.userId && creator.role == EventRole.PRIMARY_ADMIN) {
            "The event creator must be its primary admin."
        }
        require(event.memberIds == setOf(creator.userId)) {
            "A new event must initially contain only its creator."
        }
        val eventRef = firestore.collection(EVENTS).document(event.id)
        firestore.runBatch { batch ->
            batch.set(eventRef, event.toRemoteMap())
            batch.set(eventRef.collection(MEMBERS).document(creator.userId), creator.toRemoteMap())
        }.await()
    }

    override suspend fun listEvents(): List<CommunityEvent> {
        val uid = requireUserId()
        val public = firestore.collection(EVENTS)
            .whereEqualTo("visibility", EventVisibility.PUBLIC.name)
            .get().await().documents
        val joined = if (auth.currentUser?.isAnonymous == false) {
            firestore.collection(EVENTS).whereArrayContains("memberIds", uid)
                .get().await().documents
        } else emptyList()
        return (public + joined)
            .distinctBy(DocumentSnapshot::getId)
            .mapNotNull { document -> document.data?.toEvent(document.id) }
            .sortedBy(CommunityEvent::startsAt)
    }

    override suspend fun updateEvent(event: CommunityEvent) {
        firestore.collection(EVENTS).document(event.id).set(event.toRemoteMap()).await()
    }

    override suspend fun deleteEvent(eventId: String) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        deleteCollection(eventRef.collection(ANNOUNCEMENTS))
        deleteCollection(eventRef.collection(MEMBERS))
        deleteInvitationsForEvent(eventId)
        eventRef.delete().await()
    }

    override fun observeEvents(
        onEvents: (events: List<CommunityEvent>, authoritative: Boolean) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        val user = auth.currentUser
        val uid = user?.uid
        if (uid.isNullOrBlank()) {
            onError(IllegalStateException("Could not connect to events."))
            return AutoCloseable { }
        }
        val publicEvents = linkedMapOf<String, CommunityEvent>()
        val joinedEvents = linkedMapOf<String, CommunityEvent>()
        var publicLoaded = false
        var joinedLoaded = user.isAnonymous
        var publicServer = false
        var joinedServer = user.isAnonymous

        fun publish() {
            if (!publicLoaded || !joinedLoaded) return
            onEvents(
                (publicEvents.values + joinedEvents.values).distinctBy(CommunityEvent::id)
                    .sortedBy(CommunityEvent::startsAt),
                publicServer && joinedServer,
            )
        }

        val registrations = mutableListOf<ListenerRegistration>()
        registrations += firestore.collection(EVENTS)
            .whereEqualTo("visibility", EventVisibility.PUBLIC.name)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error)
                else if (snapshot != null) {
                    publicEvents.clear()
                    snapshot.documents.mapNotNull { it.data?.toEvent(it.id) }
                        .associateByTo(publicEvents, CommunityEvent::id)
                    publicLoaded = true
                    publicServer = !snapshot.metadata.isFromCache
                    publish()
                }
            }
        if (!user.isAnonymous) {
            registrations += firestore.collection(EVENTS)
                .whereArrayContains("memberIds", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) onError(error)
                    else if (snapshot != null) {
                        joinedEvents.clear()
                        snapshot.documents.mapNotNull { it.data?.toEvent(it.id) }
                            .associateByTo(joinedEvents, CommunityEvent::id)
                        joinedLoaded = true
                        joinedServer = !snapshot.metadata.isFromCache
                        publish()
                    }
                }
        }
        return AutoCloseable { registrations.forEach(ListenerRegistration::remove) }
    }

    override fun observeInvitations(
        onInvitations: (List<EventInvitation>) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid
        if (uid.isNullOrBlank()) {
            onInvitations(emptyList())
            return AutoCloseable { }
        }
        val registration = firestore.collection(INVITATIONS)
            .whereEqualTo("recipientUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) onError(error)
                else onInvitations(snapshot?.documents.orEmpty().mapNotNull { it.toInvitation() })
            }
        return AutoCloseable(registration::remove)
    }

    override suspend fun listInvitations(): List<EventInvitation> {
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid ?: return emptyList()
        return firestore.collection(INVITATIONS).whereEqualTo("recipientUid", uid)
            .get().await().documents.mapNotNull { it.toInvitation() }
    }

    override suspend fun listEventInvitations(eventId: String): List<EventInvitation> {
        requireAccountUserId()
        return firestore.collection(INVITATIONS).whereEqualTo("eventId", eventId)
            .get().await().documents.mapNotNull { it.toInvitation() }
    }

    override suspend fun findInvitee(identifier: String): EventInvitee? {
        val currentUid = requireAccountUserId()
        val clean = identifier.trim()
        val uid = if (clean.startsWith("@") || '@' !in clean) {
            val username = clean.removePrefix("@").lowercase(Locale.ROOT)
            if (!USERNAME_PATTERN.matches(username)) return null
            firestore.collection(USERNAMES).document(username).get().await().getString("uid")
        } else {
            val email = clean.lowercase(Locale.ROOT)
            if (!EMAIL_PATTERN.matches(email)) return null
            firestore.collection(EMAIL_LOOKUP).document(email).collection(ACCOUNTS)
                .limit(2).get().await().documents.singleOrNull()?.id
        } ?: return null
        if (uid == currentUid) return null
        val card = firestore.collection(ACCOUNT_CARDS).document(uid).get().await()
        if (!card.exists() || card.getString("uid") != uid) return null
        return EventInvitee(
            uid = uid,
            displayName = card.getString("name").orEmpty().take(24),
            username = card.getString("username").orEmpty(),
        )
    }

    override suspend fun invite(invitation: EventInvitation) {
        require(requireAccountUserId() == invitation.inviterUid)
        firestore.collection(INVITATIONS).document(invitation.id).set(invitation.toRemoteMap()).await()
    }

    override suspend fun acceptInvitation(invitation: EventInvitation, membership: EventMembership) {
        val uid = requireAccountUserId()
        require(invitation.recipientUid == uid && membership.userId == uid)
        val invitationRef = firestore.collection(INVITATIONS).document(invitation.id)
        val eventRef = firestore.collection(EVENTS).document(invitation.eventId)
        firestore.runBatch { batch ->
            batch.update(invitationRef, "status", EventInvitationStatus.ACCEPTED.name)
            batch.update(eventRef, "memberIds", FieldValue.arrayUnion(uid))
            batch.set(eventRef.collection(MEMBERS).document(uid), membership.toRemoteMap())
        }.await()
    }

    override suspend fun declineInvitation(invitation: EventInvitation) {
        require(requireAccountUserId() == invitation.recipientUid)
        firestore.collection(INVITATIONS).document(invitation.id)
            .update("status", EventInvitationStatus.DECLINED.name).await()
    }

    override suspend fun revokeInvitation(invitation: EventInvitation) {
        requireAccountUserId()
        firestore.collection(INVITATIONS).document(invitation.id)
            .update("status", EventInvitationStatus.REVOKED.name).await()
    }

    private suspend fun deleteCollection(collection: com.google.firebase.firestore.CollectionReference) {
        while (true) {
            val documents = collection.limit(DELETE_BATCH_SIZE.toLong()).get().await().documents
            if (documents.isEmpty()) return
            val batch = firestore.batch()
            documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private suspend fun deleteInvitationsForEvent(eventId: String) {
        while (true) {
            val documents = firestore.collection(INVITATIONS).whereEqualTo("eventId", eventId)
                .limit(DELETE_BATCH_SIZE.toLong()).get().await().documents
            if (documents.isEmpty()) return
            val batch = firestore.batch()
            documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    override suspend fun joinEvent(membership: EventMembership) {
        val eventRef = firestore.collection(EVENTS).document(membership.eventId)
        firestore.runBatch { batch ->
            batch.update(eventRef, "memberIds", FieldValue.arrayUnion(membership.userId))
            batch.set(eventRef.collection(MEMBERS).document(membership.userId), membership.toRemoteMap())
        }.await()
    }

    override suspend fun leaveEvent(eventId: String, userId: String, leftAt: Long) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        firestore.runBatch { batch ->
            batch.update(eventRef, "memberIds", FieldValue.arrayRemove(userId))
            batch.update(eventRef.collection(MEMBERS).document(userId), "leftAt", leftAt)
        }.await()
    }

    override suspend fun listMembers(eventId: String): List<EventMembership> = firestore
        .collection(EVENTS).document(eventId).collection(MEMBERS).get().await().documents
        .mapNotNull { document -> document.data?.toMembership(eventId, document.id) }

    override suspend fun promoteToCoAdmin(eventId: String, userId: String) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        firestore.runBatch { batch ->
            batch.update(
                eventRef,
                mapOf(
                    "adminIds" to FieldValue.arrayUnion(userId),
                    "memberIds" to FieldValue.arrayUnion(userId),
                ),
            )
            batch.update(eventRef.collection(MEMBERS).document(userId), "role", EventRole.CO_ADMIN.name)
        }.await()
    }

    override suspend fun blockMember(eventId: String, userId: String, blockedAt: Long) {
        val eventRef = firestore.collection(EVENTS).document(eventId)
        firestore.runBatch { batch ->
            batch.update(
                eventRef,
                mapOf(
                    "adminIds" to FieldValue.arrayRemove(userId),
                    "memberIds" to FieldValue.arrayRemove(userId),
                ),
            )
            batch.update(eventRef.collection(MEMBERS).document(userId), "blockedAt", blockedAt)
        }.await()
    }

    override suspend fun registerAdminPublicKey(eventId: String, userId: String, encodedPublicKey: String) {
        firestore.collection(EVENTS).document(eventId)
            .update("adminPublicKeys.$userId", encodedPublicKey).await()
    }

    override suspend fun saveAnnouncement(announcement: EventAnnouncement) {
        firestore.collection(EVENTS).document(announcement.eventId).collection(ANNOUNCEMENTS)
            .document(announcement.id).set(announcement.toRemoteMap()).await()
    }

    override suspend fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> =
        firestore.collection(EVENTS).document(eventId).collection(ANNOUNCEMENTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1, 500).toLong()).get().await().documents
            .mapNotNull { document -> document.data?.toAnnouncement(document.id, eventId) }.asReversed()

    private fun CommunityEvent.toRemoteMap(): Map<String, Any?> = mapOf(
        "title" to title, "description" to description, "venueName" to venueName,
        "latitude" to latitude, "longitude" to longitude, "radiusMetres" to radiusMetres,
        "startsAt" to startsAt, "endsAt" to endsAt, "createdBy" to createdBy,
        "adminIds" to adminIds.toList(), "memberIds" to memberIds.toList(),
        "adminPublicKeys" to adminPublicKeys, "visibility" to visibility.name,
        "requiresSignIn" to requiresSignIn, "privateMeshSecret" to privateMeshSecret, "createdAt" to createdAt,
        "updatedAt" to updatedAt, "deletedAt" to deletedAt,
    )

    private fun EventMembership.toRemoteMap(): Map<String, Any?> = mapOf(
        "userId" to userId, "displayName" to displayName, "role" to role.name,
        "joinedAt" to joinedAt, "blockedAt" to blockedAt, "leftAt" to leftAt,
        "accessMethod" to accessMethod?.name, "checkedInAt" to checkedInAt,
    )

    private fun EventAnnouncement.toRemoteMap(): Map<String, Any> = mapOf(
        "adminId" to adminId, "adminName" to adminName, "text" to text,
        "createdAt" to createdAt, "revision" to revision, "signature" to signature,
    )

    private fun EventInvitation.toRemoteMap(): Map<String, Any> = mapOf(
        "eventId" to eventId, "eventTitle" to eventTitle, "inviterUid" to inviterUid,
        "inviterName" to inviterName, "recipientUid" to recipientUid,
        "recipientName" to recipientName, "recipientUsername" to recipientUsername,
        "startsAt" to startsAt, "endsAt" to endsAt, "createdAt" to createdAt,
        "expiresAt" to expiresAt, "status" to status.name,
    )

    private fun Map<String, Any>.toEvent(id: String): CommunityEvent? = runCatching {
        val creator = getValue("createdBy") as String
        val admins = (get("adminIds") as? List<*>)?.filterIsInstance<String>()?.toSet()?.plus(creator)
            ?: setOf(creator)
        val members = (get("memberIds") as? List<*>)?.filterIsInstance<String>()?.toSet()
            ?.plus(admins) ?: admins
        val visibility = (get("visibility") as? String)?.let(EventVisibility::valueOf)
            ?: EventVisibility.PUBLIC
        val publicKeys = (get("adminPublicKeys") as? Map<*, *>)?.mapNotNull { (key, value) ->
            if (key is String && value is String) key to value else null
        }?.toMap() ?: emptyMap()
        CommunityEvent(
            id = id, title = getValue("title") as String,
            description = get("description") as? String ?: "", venueName = get("venueName") as? String ?: "",
            latitude = (getValue("latitude") as Number).toDouble(),
            longitude = (getValue("longitude") as Number).toDouble(),
            radiusMetres = (getValue("radiusMetres") as Number).toDouble(),
            startsAt = (getValue("startsAt") as Number).toLong(),
            endsAt = (getValue("endsAt") as Number).toLong(), createdBy = creator,
            adminIds = admins, memberIds = members, adminPublicKeys = publicKeys,
            visibility = visibility, requiresSignIn = get("requiresSignIn") as? Boolean ?: false,
            privateMeshSecret = get("privateMeshSecret") as? String ?: "",
            createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
            updatedAt = (get("updatedAt") as? Number)?.toLong()
                ?: (get("createdAt") as? Number)?.toLong() ?: 0L,
            deletedAt = (get("deletedAt") as? Number)?.toLong(),
        )
    }.getOrNull()

    private fun DocumentSnapshot.toInvitation(): EventInvitation? = runCatching {
        EventInvitation(
            id = id, eventId = getString("eventId") ?: return null,
            eventTitle = getString("eventTitle") ?: return null,
            inviterUid = getString("inviterUid") ?: return null,
            inviterName = getString("inviterName").orEmpty(),
            recipientUid = getString("recipientUid") ?: return null,
            recipientName = getString("recipientName").orEmpty(),
            recipientUsername = getString("recipientUsername").orEmpty(),
            startsAt = getLong("startsAt") ?: return null, endsAt = getLong("endsAt") ?: return null,
            createdAt = getLong("createdAt") ?: 0L, expiresAt = getLong("expiresAt") ?: return null,
            status = getString("status")?.let(EventInvitationStatus::valueOf)
                ?: EventInvitationStatus.PENDING,
        )
    }.getOrNull()

    private fun Map<String, Any>.toAnnouncement(id: String, eventId: String): EventAnnouncement? = runCatching {
        EventAnnouncement(
            id = id, eventId = eventId, adminId = getValue("adminId") as String,
            adminName = getValue("adminName") as String, text = getValue("text") as String,
            createdAt = (getValue("createdAt") as Number).toLong(),
            revision = (getValue("revision") as Number).toLong(),
            signature = getValue("signature") as String, syncedToCloud = true,
        )
    }.getOrNull()

    private fun Map<String, Any>.toMembership(eventId: String, userId: String): EventMembership? = runCatching {
        EventMembership(
            eventId = eventId, userId = userId,
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
        const val INVITATIONS = "eventInvitations"
        const val USERNAMES = "usernames"
        const val EMAIL_LOOKUP = "emailLookup"
        const val ACCOUNTS = "accounts"
        const val ACCOUNT_CARDS = "accountCards"
        const val DELETE_BATCH_SIZE = 450
        val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,20}$")
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
