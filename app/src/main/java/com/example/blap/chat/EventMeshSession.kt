package com.example.blap.chat

import java.security.MessageDigest

/** Event-specific Nearby session identity. Does not own connections or packet routing. */
class EventMeshSession {
    var eventId: String? = null
        private set
    var meshSecret: String = ""
        private set
    var userId: String = ""
        private set
    var accessGranted: Boolean = false
        private set

    private val eventEndpoints = mutableSetOf<String>()

    val isEventMode: Boolean
        get() = eventId != null

    fun eventEndpoints(): Set<String> = eventEndpoints.toSet()

    fun containsEndpoint(endpointId: String): Boolean = endpointId in eventEndpoints

    fun markEndpoint(endpointId: String) {
        eventEndpoints += endpointId
    }

    fun removeEndpoint(endpointId: String): Boolean = eventEndpoints.remove(endpointId)

    /** Clears transport connections while retaining the active event identity for a later resume. */
    fun clearEndpoints() {
        eventEndpoints.clear()
    }

    fun currentServiceId(): String = eventId
        ?.let { eventServiceId(it, meshSecret) }
        ?: SERVICE_ID

    fun advertisedEndpointName(localPeerId: String, displayName: String): String =
        if (eventId == null) displayName else "$EVENT_ENDPOINT_PREFIX$localPeerId"

    /**
     * Returns true when advertising/discovery must restart because the event identity changed.
     * Updating only the local user or access flag keeps the current Nearby service.
     */
    fun setActiveEvent(
        eventId: String?,
        meshSecret: String = "",
        userId: String = "",
        accessGranted: Boolean = true,
    ): Boolean {
        if (eventId == this.eventId && meshSecret == this.meshSecret) {
            this.userId = userId
            this.accessGranted = accessGranted && eventId != null
            return false
        }
        this.eventId = eventId
        this.meshSecret = meshSecret
        this.userId = userId
        this.accessGranted = accessGranted && eventId != null
        eventEndpoints.clear()
        return true
    }

    fun presencePacket(localPeerId: String, displayName: String, eventId: String?): NearbyPacket.EventPresence =
        NearbyPacket.EventPresence(
            eventId = eventId.orEmpty(),
            peerId = localPeerId,
            name = displayName,
            userId = userId,
            active = eventId != null,
            accessGranted = accessGranted,
        )

    fun clear() {
        eventId = null
        meshSecret = ""
        userId = ""
        accessGranted = false
        clearEndpoints()
    }

    companion object {
        const val SERVICE_ID = "com.example.blap"
        const val EVENT_ENDPOINT_PREFIX = "cg-event|"
        const val EVENT_ATTENDEE_NAME = "Event attendee"
        const val EVENT_SERVICE_HASH_BYTES = 12
        const val MAX_EVENT_HISTORY = 50
        const val MAX_EVENT_ANNOUNCEMENT_HISTORY = 100

        fun eventServiceId(eventId: String, meshSecret: String = ""): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$eventId|$meshSecret".toByteArray())
            val token = digest.take(EVENT_SERVICE_HASH_BYTES)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "$SERVICE_ID.event.$token"
        }

        fun eventPeerIdOrNull(endpointName: String): String? = endpointName
            .takeIf { it.startsWith(EVENT_ENDPOINT_PREFIX) }
            ?.removePrefix(EVENT_ENDPOINT_PREFIX)
            ?.takeIf(String::isNotBlank)

        fun shouldInitiateEventConnection(localPeerId: String, remotePeerId: String): Boolean =
            localPeerId.isNotBlank() && remotePeerId.isNotBlank() && localPeerId < remotePeerId
    }
}
