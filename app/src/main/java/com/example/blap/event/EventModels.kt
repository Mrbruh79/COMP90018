package com.example.blap.event

import java.util.UUID
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class EventRole {
    PRIMARY_ADMIN,
    CO_ADMIN,
    ATTENDEE,
}

enum class EventAccessMethod {
    GPS,
    VENUE_QR,
    ADMIN_APPROVAL,
}

enum class EventVisibility {
    PUBLIC,
    PRIVATE,
}

enum class EventInvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED,
}

data class CommunityEvent(
    val id: String,
    val title: String,
    val description: String,
    val venueName: String = "",
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Double = DEFAULT_RADIUS_METRES,
    val startsAt: Long,
    val endsAt: Long,
    val createdBy: String,
    val adminIds: Set<String> = setOf(createdBy),
    val memberIds: Set<String> = adminIds,
    val adminPublicKeys: Map<String, String> = emptyMap(),
    val visibility: EventVisibility = EventVisibility.PUBLIC,
    val requiresSignIn: Boolean = false,
    val privateMeshSecret: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Event ID cannot be blank." }
        require(title.isNotBlank()) { "Event title cannot be blank." }
        require(latitude in -90.0..90.0) { "Invalid event latitude." }
        require(longitude in -180.0..180.0) { "Invalid event longitude." }
        require(radiusMetres in MIN_RADIUS_METRES..MAX_RADIUS_METRES) { "Invalid event radius." }
        require(endsAt > startsAt) { "Event must end after it starts." }
        require(createdBy.isNotBlank()) { "Event creator cannot be blank." }
        require(createdBy in adminIds) { "The event creator must remain an admin." }
        require(createdBy in memberIds) { "The event creator must remain a member." }
        require(adminIds.all(memberIds::contains)) { "Every event admin must remain a member." }
        require(visibility != EventVisibility.PRIVATE || privateMeshSecret.isNotBlank()) {
            "Private events require a mesh secret."
        }
    }

    fun isAdmin(userId: String): Boolean = userId in adminIds

    fun isActive(now: Long): Boolean = deletedAt == null && now in startsAt..endsAt

    val isDeleted: Boolean
        get() = deletedAt != null

    companion object {
        const val DEFAULT_RADIUS_METRES = 100.0
        const val MIN_RADIUS_METRES = 20.0
        const val MAX_RADIUS_METRES = 5_000.0
    }
}

object EventSecrets {
    private val random = SecureRandom()

    fun newMeshSecret(): String = ByteArray(32)
        .also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
}

data class EventInvitation(
    val id: String,
    val eventId: String,
    val eventTitle: String,
    val inviterUid: String,
    val inviterName: String,
    val recipientUid: String,
    val recipientName: String,
    val recipientUsername: String,
    val startsAt: Long,
    val endsAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = endsAt,
    val status: EventInvitationStatus = EventInvitationStatus.PENDING,
) {
    val isPending: Boolean
        get() = status == EventInvitationStatus.PENDING && System.currentTimeMillis() <= expiresAt
}

data class EventInvitee(
    val uid: String,
    val displayName: String,
    val username: String,
)

data class EventAccessRequest(
    val id: String = UUID.randomUUID().toString(),
    val eventId: String,
    val userId: String,
    val peerId: String,
    val displayName: String,
    val requestedAt: Long = System.currentTimeMillis(),
)

data class EventAccessGrant(
    val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val eventId: String,
    val userId: String,
    val peerId: String,
    val adminId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: String = "",
)

data class EventMutation(
    val event: CommunityEvent,
    val adminId: String,
    val signature: String = "",
)

data class EventMembership(
    val eventId: String,
    val userId: String,
    val displayName: String,
    val role: EventRole,
    val joinedAt: Long,
    val blockedAt: Long? = null,
    val leftAt: Long? = null,
    val accessMethod: EventAccessMethod? = null,
    val checkedInAt: Long? = null,
) {
    val canParticipate: Boolean
        get() = blockedAt == null && leftAt == null

    val isAdmin: Boolean
        get() = role == EventRole.PRIMARY_ADMIN || role == EventRole.CO_ADMIN
}

data class EventAnnouncement(
    val id: String = UUID.randomUUID().toString(),
    val eventId: String,
    val adminId: String,
    val adminName: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = createdAt,
    val signature: String = "",
    val syncedToCloud: Boolean = false,
)

data class EventChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val eventId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class EventCreateRequest(
    val title: String,
    val description: String,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMetres: Double,
    val startsAt: Long,
    val endsAt: Long,
    val visibility: EventVisibility = EventVisibility.PUBLIC,
    val requiresSignIn: Boolean = false,
)

sealed interface EventEntryDecision {
    data class Allowed(val method: EventAccessMethod) : EventEntryDecision
    data class NeedsQr(val reason: String) : EventEntryDecision
    data class Denied(val reason: String) : EventEntryDecision
}

object EventAccessPolicy {
    const val MAX_TRUSTED_GPS_ACCURACY_METRES = 50.0
    const val MAX_CLOCK_SKEW_MILLIS = 2 * 60 * 1_000L

    fun evaluateGps(
        event: CommunityEvent,
        membership: EventMembership?,
        currentLatitude: Double,
        currentLongitude: Double,
        accuracyMetres: Double,
        now: Long = System.currentTimeMillis(),
    ): EventEntryDecision {
        val membershipDecision = validateMembershipAndTime(event, membership, now)
        if (membershipDecision != null) return membershipDecision
        if (!accuracyMetres.isFinite() || accuracyMetres <= 0.0 ||
            accuracyMetres > MAX_TRUSTED_GPS_ACCURACY_METRES
        ) {
            return EventEntryDecision.NeedsQr("GPS is not accurate enough. Scan the venue check-in QR.")
        }

        val distance = distanceMetres(
            currentLatitude,
            currentLongitude,
            event.latitude,
            event.longitude,
        )
        return if (distance <= event.radiusMetres + accuracyMetres) {
            EventEntryDecision.Allowed(EventAccessMethod.GPS)
        } else {
            EventEntryDecision.Denied("You are outside this event's on-site area.")
        }
    }

    fun evaluateQr(
        event: CommunityEvent,
        membership: EventMembership?,
        credential: EventCheckInCredential,
        now: Long = System.currentTimeMillis(),
    ): EventEntryDecision {
        val membershipDecision = validateMembershipAndTime(event, membership, now)
        if (membershipDecision != null) return membershipDecision
        if (credential.eventId != event.id) {
            return EventEntryDecision.Denied("This check-in code belongs to another event.")
        }
        if (credential.adminId !in event.adminIds) {
            return EventEntryDecision.Denied("This check-in code was not issued by an event admin.")
        }
        if (now + MAX_CLOCK_SKEW_MILLIS < credential.issuedAt ||
            now - MAX_CLOCK_SKEW_MILLIS > credential.expiresAt
        ) {
            return EventEntryDecision.Denied("This check-in code has expired.")
        }
        return EventEntryDecision.Allowed(EventAccessMethod.VENUE_QR)
    }

    private fun validateMembershipAndTime(
        event: CommunityEvent,
        membership: EventMembership?,
        now: Long,
    ): EventEntryDecision? = when {
        event.isDeleted -> EventEntryDecision.Denied("This event has been deleted by its admin.")
        membership == null || membership.eventId != event.id ->
            EventEntryDecision.Denied("Join this event before entering on-site chat.")

        membership.blockedAt != null ->
            EventEntryDecision.Denied("You have been removed from this event.")

        membership.leftAt != null ->
            EventEntryDecision.Denied("Rejoin this event before entering on-site chat.")

        now < event.startsAt -> EventEntryDecision.Denied("On-site chat opens when the event starts.")
        now > event.endsAt -> EventEntryDecision.Denied("This event has ended. Chat history is read-only.")
        else -> null
    }

    fun distanceMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
