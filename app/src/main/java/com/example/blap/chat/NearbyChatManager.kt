package com.example.blap.chat

import android.content.Context
import com.example.blap.event.EventAccessGrant
import com.example.blap.event.EventAccessRequest
import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation

class NearbyChatManager internal constructor(
    private val connections: NearbyConnectionTransport,
    private val meshRouter: MeshRouter = MeshRouter(),
    private val eventSession: EventMeshSession = EventMeshSession(),
) : NearbyChatController, NearbyConnectionTransport.Listener {
    constructor(context: Context) : this(GoogleNearbyTransport(context))

    private val knownDevices = mutableMapOf<String, NearbyDevice>()
    private val pendingDevices = mutableMapOf<String, NearbyDevice>()
    private val establishedEndpoints = mutableSetOf<String>()
    private val peerByEndpoint = mutableMapOf<String, ConnectedPeer>()
    private val endpointByPeer = mutableMapOf<String, String>()

    private var localDisplayName = ""
    private var localPeerId = ""
    private var localPhoneHash = ""
    private var advertisingRequested = false
    private var discoveryRequested = false

    override var listener: NearbyTransport.Listener? = null

    init {
        connections.listener = this
    }

    override fun startAdvertising(displayName: String, peerId: String, phoneHash: String) {
        localDisplayName = displayName
        localPeerId = peerId
        localPhoneHash = phoneHash
        meshRouter.rememberPeer(GroupMember(peerId, displayName, phoneHash))
        advertisingRequested = true
        startAdvertisingForCurrentMode()
    }

    private fun startAdvertisingForCurrentMode() {
        connections.stopAdvertising()
        connections.startAdvertising(advertisedEndpointName(), eventSession.currentServiceId())
    }

    override fun startDiscovery() {
        discoveryRequested = true
        startDiscoveryForCurrentMode()
    }

    private fun startDiscoveryForCurrentMode() {
        connections.stopDiscovery()
        connections.startDiscovery(eventSession.currentServiceId())
    }

    override fun connectToDevice(endpointId: String) {
        if (endpointId in establishedEndpoints || endpointId in pendingDevices) return
        val device = knownDevices[endpointId] ?: return
        pendingDevices[endpointId] = device
        connections.requestConnection(localDisplayName, endpointId)
    }

    override fun sendMessage(message: OutgoingNearbyMessage) {
        if (eventSession.isEventMode) return
        val packet = NearbyPacket.Message(
            messageId = message.messageId,
            senderId = localPeerId,
            senderName = localDisplayName,
            senderPhoneHash = localPhoneHash,
            recipientId = message.peerId,
            sentAt = message.sentAt,
            text = message.text,
            hopsRemaining = MeshRouter.MAX_HOPS,
            isGroup = message.isGroup,
        )

        if (message.isGroup) {
            meshRouter.noteOutgoingMessage(message.messageId)
            meshRouter.rememberGroupMessage(packet)
            sendPacketToMany(establishedEndpoints, packet) {
                listener?.onMessageSent(message.peerId, message.messageId)
            }
        } else {
            val endpointId = endpointByPeer[message.peerId] ?: return
            sendPacketWithResult(endpointId, packet) {
                listener?.onMessageSent(message.peerId, message.messageId)
            }
        }
    }

    override fun publishGroup(group: PrivateGroup) {
        if (eventSession.isEventMode) return
        val packet = group.toPacket()
        meshRouter.noteOutgoingGroup(packet)
        sendPacketToMany(establishedEndpoints, packet)
    }

    override fun synchronizeGroups(
        peerId: String,
        groups: List<PrivateGroup>,
        messages: List<StoredGroupMessage>,
    ) {
        if (eventSession.isEventMode) return
        val endpointId = endpointByPeer[peerId] ?: return
        groups.forEach { group ->
            val packet = group.toPacket()
            meshRouter.noteOutgoingGroup(packet)
            sendPacket(endpointId, packet)
        }
        messages.forEach { message ->
            meshRouter.noteOutgoingMessage(message.messageId)
            val packet = NearbyPacket.Message(
                messageId = message.messageId,
                senderId = message.senderId,
                senderName = message.senderName,
                senderPhoneHash = message.senderPhoneHash,
                recipientId = message.conversationId,
                sentAt = message.sentAt,
                text = message.text,
                hopsRemaining = MeshRouter.MAX_HOPS,
                isGroup = true,
            )
            meshRouter.rememberGroupMessage(packet)
            sendPacketWithResult(endpointId, packet) {
                listener?.onMessageSent(message.conversationId, message.messageId)
            }
        }
    }

    override fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String) {
        if (eventSession.isEventMode) return
        val packet = NearbyPacket.Acknowledgement(
            messageId = messageId,
            senderId = localPeerId,
            recipientId = senderId,
            conversationId = conversationId,
            hopsRemaining = MeshRouter.MAX_HOPS,
            isGroup = conversationId != senderId,
        )
        if (packet.isGroup) {
            meshRouter.noteOutgoingAcknowledgement(packet)
            sendPacketToMany(establishedEndpoints, packet)
        } else {
            val endpointId = endpointByPeer[senderId] ?: return
            sendPacket(endpointId, packet)
        }
    }

    override fun setActiveEvent(
        eventId: String?,
        meshSecret: String,
        userId: String,
        accessGranted: Boolean,
    ) {
        val switched = eventId != eventSession.eventId || meshSecret != eventSession.meshSecret
        if (!switched) {
            eventSession.setActiveEvent(eventId, meshSecret, userId, accessGranted)
            broadcastEventPresence(eventId)
            return
        }
        broadcastEventPresence(null)
        eventSession.setActiveEvent(eventId, meshSecret, userId, accessGranted)
        restartForCurrentMode()
    }

    override fun sendEventChatMessage(message: EventChatMessage) {
        if (!eventSession.accessGranted || message.eventId != eventSession.eventId || message.text.isBlank()) return
        val packet = message.toPacket()
        meshRouter.noteOutgoingEventMessage(message.id)
        sendPacketToMany(eventSession.eventEndpoints(), packet) {
            listener?.onEventMessageSent(message.eventId, message.id)
        }
    }

    override fun sendEventAnnouncement(announcement: EventAnnouncement) {
        if (!eventSession.accessGranted || announcement.eventId != eventSession.eventId) return
        val packet = announcement.toPacket()
        meshRouter.noteOutgoingEventAnnouncement(packet)
        sendPacketToMany(eventSession.eventEndpoints(), packet)
    }

    override fun sendEventMutation(mutation: EventMutation) {
        if (!eventSession.accessGranted || mutation.event.id != eventSession.eventId) return
        val packet = mutation.toPacket()
        meshRouter.noteOutgoingEventMutation(packet)
        sendPacketToMany(eventSession.eventEndpoints(), packet)
    }

    override fun sendEventAccessRequest(request: EventAccessRequest) {
        if (request.eventId != eventSession.eventId || request.userId != eventSession.userId) return
        val packet = request.toPacket()
        meshRouter.noteOutgoingEventAccessRequest(request.id)
        sendPacketToMany(eventSession.eventEndpoints(), packet)
    }

    override fun sendEventAccessGrant(grant: EventAccessGrant) {
        if (!eventSession.accessGranted || grant.eventId != eventSession.eventId) return
        val packet = grant.toPacket()
        meshRouter.noteOutgoingEventAccessGrant(grant.id)
        sendPacketToMany(eventSession.eventEndpoints(), packet)
    }

    override fun synchronizeEventHistory(peerId: String, messages: List<EventChatMessage>) {
        val endpointId = endpointByPeer[peerId] ?: return
        messages.takeLast(EventMeshSession.MAX_EVENT_HISTORY).forEach { message ->
            meshRouter.noteOutgoingEventMessage(message.id)
            sendPacket(endpointId, message.toPacket())
        }
    }

    override fun synchronizeEventAnnouncements(
        peerId: String,
        announcements: List<EventAnnouncement>,
    ) {
        val eventId = eventSession.eventId ?: return
        val endpointId = endpointByPeer[peerId]?.takeIf(eventSession::containsEndpoint) ?: return
        announcements
            .filter { announcement ->
                announcement.eventId == eventId && announcement.signature.isNotBlank()
            }
            .takeLast(EventMeshSession.MAX_EVENT_ANNOUNCEMENT_HISTORY)
            .forEach { announcement ->
                val packet = announcement.toPacket()
                meshRouter.noteOutgoingEventAnnouncement(packet)
                sendPacket(endpointId, packet)
            }
    }

    override fun disconnect(peerId: String) {
        val endpointId = endpointByPeer[peerId] ?: return
        connections.disconnect(endpointId)
    }

    override fun stop() {
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.disconnectAll()
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        meshRouter.clear()
        eventSession.clear()
        advertisingRequested = false
        discoveryRequested = false
    }

    override fun close() {
        stop()
        listener = null
        connections.listener = null
    }

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        if (endpointId in establishedEndpoints || endpointId in pendingDevices) return
        val eventPeerId = EventMeshSession.eventPeerIdOrNull(endpointName)
        val isEventDiscovery = eventSession.isEventMode
        if (isEventDiscovery && (eventPeerId == null || eventPeerId == localPeerId)) return
        val device = NearbyDevice(
            endpointId,
            if (isEventDiscovery) EventMeshSession.EVENT_ATTENDEE_NAME else endpointName,
        )
        knownDevices[endpointId] = device
        if (isEventDiscovery) {
            if (EventMeshSession.shouldInitiateEventConnection(localPeerId, requireNotNull(eventPeerId))) {
                connectToDevice(endpointId)
            }
        } else {
            listener?.onDeviceFound(device)
        }
    }

    override fun onEndpointLost(endpointId: String) {
        knownDevices.remove(endpointId)
        listener?.onDeviceLost(endpointId)
    }

    override fun onConnectionInitiated(endpointId: String, endpointName: String, authenticationDigits: String) {
        if (endpointId in establishedEndpoints) {
            connections.rejectConnection(endpointId)
            return
        }

        val isEventConnection = eventSession.isEventMode
        if (isEventConnection) eventSession.markEndpoint(endpointId)
        val device = NearbyDevice(
            endpointId,
            if (isEventConnection) EventMeshSession.EVENT_ATTENDEE_NAME else endpointName,
        )
        pendingDevices[endpointId] = device
        if (!isEventConnection) {
            listener?.onConnectionInitiated(device, authenticationDigits)
        }
        connections.acceptConnection(endpointId)
    }

    override fun onConnectionSucceeded(endpointId: String) {
        establishedEndpoints += endpointId
        pendingDevices.remove(endpointId)
        knownDevices.remove(endpointId)
        sendPacket(endpointId, NearbyPacket.Hello(localPeerId, localDisplayName, localPhoneHash))
    }

    override fun onConnectionFailed(endpointId: String, message: String) {
        pendingDevices.remove(endpointId)
        listener?.onError(message)
    }

    override fun onDisconnected(endpointId: String) {
        establishedEndpoints.remove(endpointId)
        pendingDevices.remove(endpointId)
        val wasEventConnection = eventSession.removeEndpoint(endpointId)
        val peer = peerByEndpoint.remove(endpointId) ?: return
        if (endpointByPeer[peer.peerId] == endpointId) {
            endpointByPeer.remove(peer.peerId)
            if (!wasEventConnection) listener?.onDisconnected(peer.peerId)
        }
    }

    override fun onBytesReceived(endpointId: String, bytes: ByteArray) {
        if (endpointId !in establishedEndpoints) return
        val packet = NearbyProtocol.decode(bytes) ?: return
        if (packet is NearbyPacket.Hello) {
            handleHello(endpointId, packet)
            return
        }
        applyDecision(meshRouter.ingest(packet, meshContext(endpointId)))
    }

    override fun onError(message: String) {
        listener?.onError(message)
    }

    override fun onUnavailable(message: String) {
        listener?.onNearbyUnavailable(message)
    }

    private fun handleHello(endpointId: String, packet: NearbyPacket.Hello) {
        if (packet.peerId.isBlank() || packet.name.isBlank() || packet.peerId == localPeerId) return

        val oldEndpoint = endpointByPeer.put(packet.peerId, endpointId)
        if (oldEndpoint != null && oldEndpoint != endpointId) {
            connections.disconnect(oldEndpoint)
        }

        val peer = ConnectedPeer(packet.peerId, endpointId, packet.name.take(24), packet.phoneHash)
        peerByEndpoint[endpointId] = peer
        knownDevices.remove(endpointId)
        if (endpointId in eventSession.eventEndpoints() || eventSession.isEventMode) {
            eventSession.markEndpoint(endpointId)
            broadcastEventPresence(eventSession.eventId, listOf(endpointId))
            return
        }

        meshRouter.rememberPeer(GroupMember(peer.peerId, peer.name, peer.phoneHash))
        listener?.onConnected(peer)

        meshRouter.knownPeers().forEach { knownPeer ->
            sendPacket(
                endpointId,
                NearbyPacket.PeerAnnouncement(
                    peerId = knownPeer.peerId,
                    name = knownPeer.name,
                    phoneHash = knownPeer.phoneHash,
                    hopsRemaining = MeshRouter.MAX_HOPS,
                ),
            )
        }
        sendPacketToMany(
            establishedEndpoints - endpointId,
            NearbyPacket.PeerAnnouncement(peer.peerId, peer.name, peer.phoneHash, MeshRouter.MAX_HOPS),
        )
        meshRouter.cachedGroupDefinitions().forEach { definition ->
            sendPacket(endpointId, definition.copy(hopsRemaining = MeshRouter.MAX_HOPS))
        }
        meshRouter.cachedGroupMessages().forEach { message ->
            sendPacket(endpointId, message.copy(hopsRemaining = MeshRouter.MAX_HOPS))
        }
    }

    private fun restartForCurrentMode() {
        val normalPeers = peerByEndpoint
            .filterKeys { endpointId -> !eventSession.containsEndpoint(endpointId) }
            .values
            .map(ConnectedPeer::peerId)
            .distinct()
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.disconnectAll()
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        meshRouter.resetKnownPeers()
        if (localPeerId.isNotBlank()) {
            meshRouter.rememberPeer(GroupMember(localPeerId, localDisplayName, localPhoneHash))
        }
        normalPeers.forEach { peerId -> listener?.onDisconnected(peerId) }
        if (localPeerId.isBlank()) return
        if (advertisingRequested) startAdvertisingForCurrentMode()
        if (discoveryRequested) startDiscoveryForCurrentMode()
    }

    private fun broadcastEventPresence(
        eventId: String?,
        endpointIds: Collection<String> = establishedEndpoints,
    ) {
        if (localPeerId.isBlank()) return
        sendPacketToMany(
            endpointIds,
            eventSession.presencePacket(localPeerId, localDisplayName, eventId),
        )
    }

    private fun advertisedEndpointName(): String =
        eventSession.advertisedEndpointName(localPeerId, localDisplayName)

    private fun meshContext(endpointId: String) = MeshContext(
        localPeerId = localPeerId,
        fromEndpointId = endpointId,
        fromPeer = peerByEndpoint[endpointId],
        isEventEndpoint = eventSession.containsEndpoint(endpointId),
        activeEventId = eventSession.eventId,
        accessGranted = eventSession.accessGranted,
        chatForwardTargets = establishedEndpoints - endpointId,
        eventForwardTargets = eventSession.eventEndpoints() - endpointId,
    )

    private fun applyDecision(decision: MeshDecision) {
        decision.deliveries.forEach { delivery ->
            when (delivery) {
                is MeshLocalDelivery.ChatMessage -> listener?.onMessageReceived(delivery.message)
                is MeshLocalDelivery.Acknowledged ->
                    listener?.onMessageDelivered(delivery.conversationId, delivery.messageId)
                is MeshLocalDelivery.Group -> listener?.onGroupReceived(delivery.group)
                is MeshLocalDelivery.MeshPeer -> listener?.onMeshPeerFound(delivery.peer)
                is MeshLocalDelivery.EventPeer -> listener?.onEventPeerAvailable(
                    delivery.peerId,
                    delivery.eventId,
                    delivery.userId,
                    delivery.accessGranted,
                )
                is MeshLocalDelivery.EventChat -> listener?.onEventChatMessageReceived(delivery.message)
                is MeshLocalDelivery.IncomingEventAnnouncement ->
                    listener?.onEventAnnouncementReceived(delivery.announcement)
                is MeshLocalDelivery.IncomingEventMutation ->
                    listener?.onEventMutationReceived(delivery.mutation)
                is MeshLocalDelivery.IncomingEventAccessRequest ->
                    listener?.onEventAccessRequestReceived(delivery.request)
                is MeshLocalDelivery.IncomingEventAccessGrant ->
                    listener?.onEventAccessGrantReceived(delivery.grant)
            }
        }
        val packet = decision.forwardPacket ?: return
        sendPacketToMany(decision.forwardTo, packet)
    }

    private fun sendPacket(endpointId: String, packet: NearbyPacket) {
        connections.send(endpointId, NearbyProtocol.encode(packet))
    }

    private fun sendPacketWithResult(endpointId: String, packet: NearbyPacket, onSuccess: () -> Unit) {
        connections.send(endpointId, NearbyProtocol.encode(packet), onSuccess)
    }

    private fun sendPacketToMany(
        endpointIds: Collection<String>,
        packet: NearbyPacket,
        onSuccess: (() -> Unit)? = null,
    ) {
        endpointIds.forEach { endpointId ->
            if (onSuccess == null) sendPacket(endpointId, packet)
            else sendPacketWithResult(endpointId, packet, onSuccess)
        }
    }

    private fun EventChatMessage.toPacket() = NearbyPacket.EventChatMessage(
        messageId = id,
        eventId = eventId,
        senderId = senderId,
        senderName = senderName,
        text = text.take(MeshRouter.MAX_EVENT_MESSAGE_LENGTH),
        sentAt = createdAt,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    private fun EventAnnouncement.toPacket() = NearbyPacket.EventAnnouncement(
        announcementId = id,
        eventId = eventId,
        adminId = adminId,
        adminName = adminName,
        text = text.take(MeshRouter.MAX_EVENT_MESSAGE_LENGTH),
        createdAt = createdAt,
        revision = revision,
        signature = signature,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    private fun EventMutation.toPacket() = NearbyPacket.EventMutation(
        eventId = event.id,
        adminId = adminId,
        title = event.title,
        description = event.description,
        venueName = event.venueName,
        latitude = event.latitude,
        longitude = event.longitude,
        radiusMetres = event.radiusMetres,
        startsAt = event.startsAt,
        endsAt = event.endsAt,
        createdBy = event.createdBy,
        adminIds = event.adminIds.sorted(),
        adminPublicKeys = event.adminPublicKeys,
        visibility = event.visibility.name,
        requiresSignIn = event.requiresSignIn,
        venueCheckInPayload = event.venueCheckInPayload,
        createdAt = event.createdAt,
        updatedAt = event.updatedAt,
        deletedAt = event.deletedAt,
        signature = signature,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    private fun EventAccessRequest.toPacket() = NearbyPacket.EventAccessRequest(
        requestId = id,
        eventId = eventId,
        userId = userId,
        peerId = peerId,
        displayName = displayName,
        requestedAt = requestedAt,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    private fun EventAccessGrant.toPacket() = NearbyPacket.EventAccessGrant(
        grantId = id,
        requestId = requestId,
        eventId = eventId,
        userId = userId,
        peerId = peerId,
        adminId = adminId,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        signature = signature,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    private fun PrivateGroup.toPacket() = NearbyPacket.GroupDefinition(
        groupId = id,
        name = name,
        ownerId = ownerId,
        createdAt = createdAt,
        members = members,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    internal companion object {
        const val SERVICE_ID = EventMeshSession.SERVICE_ID
        const val MAX_HOPS = MeshRouter.MAX_HOPS
        const val EVENT_ENDPOINT_PREFIX = EventMeshSession.EVENT_ENDPOINT_PREFIX

        fun eventServiceId(eventId: String, meshSecret: String = ""): String =
            EventMeshSession.eventServiceId(eventId, meshSecret)

        fun String.eventPeerIdOrNull(): String? = EventMeshSession.eventPeerIdOrNull(this)

        fun shouldInitiateEventConnection(localPeerId: String, remotePeerId: String): Boolean =
            EventMeshSession.shouldInitiateEventConnection(localPeerId, remotePeerId)
    }
}
