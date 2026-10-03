package com.example.blap.chat

import android.content.Context
import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation
import com.example.blap.event.EventMeshGateway

class NearbyChatManager internal constructor(
    private val connections: NearbyConnectionTransport,
    private val meshRouter: MeshRouter = MeshRouter(),
    private val eventSession: EventMeshSession = EventMeshSession(),
    private val identities: NearbyIdentityStore = InMemoryNearbyIdentityStore(),
) : NearbyChatController, NearbyConnectionTransport.Listener {
    constructor(context: Context) : this(GoogleNearbyTransport(context))
    constructor(context: Context, identities: NearbyIdentityStore) : this(GoogleNearbyTransport(context), identities = identities)

    private val knownDevices = mutableMapOf<String, NearbyDevice>()
    private val pendingDevices = mutableMapOf<String, NearbyDevice>()
    private val establishedEndpoints = mutableSetOf<String>()
    private val approvedEndpoints = mutableSetOf<String>()
    private val awaitingApproval = mutableSetOf<String>()
    private val expectedIdentities = mutableMapOf<String, TrustedNearbyIdentity>()
    private val authenticationTokens = mutableMapOf<String, ByteArray>()
    private val peerByEndpoint = mutableMapOf<String, ConnectedPeer>()
    private val endpointByPeer = mutableMapOf<String, String>()
    private val deferredEventEndpoints = mutableMapOf<String, String>()

    private var localDisplayName = ""
    private var localPeerId = ""
    private var localPhoneHash = ""
    private var localUsername = ""
    private var localAccountUid = ""
    private var advertisingRequested = false
    private var discoveryRequested = false

    override var listener: NearbyTransport.Listener? = null
    override var eventListener: EventMeshGateway.Listener? = null

    init {
        connections.listener = this
    }

    override fun configureAccount(username: String, accountUid: String) {
        localUsername = username.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
        localAccountUid = accountUid
    }

    override fun acceptKnownConnection(endpointId: String, identity: TrustedNearbyIdentity) {
        // A claim in an advertisement is only a hint. The signed Hello must prove this key.
        if (authenticationTokens[endpointId]?.isNotEmpty() != true) {
            rejectConnection(endpointId)
            listener?.onError("Nearby could not verify this saved device. Try connecting again.")
            return
        }
        if (endpointId !in awaitingApproval) return
        expectedIdentities[endpointId] = identity
        acceptConnection(endpointId)
    }
    override fun canVerifyIdentity(endpointId: String): Boolean = authenticationTokens[endpointId]?.isNotEmpty() == true

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
        if (!discoveryRequested || eventSession.isEventMode) {
            listener?.onError("Private connections need Nearby discovery outside the event mesh.")
            return
        }
        if (pendingDevices.size >= MAX_PENDING_CONNECTIONS) {
            listener?.onError("Accept or decline the pending connections before starting another.")
            return
        }
        if (endpointId in establishedEndpoints || endpointId in pendingDevices) return
        val device = knownDevices[endpointId] ?: run {
            listener?.onConnectionClosed(endpointId)
            listener?.onError("This phone is no longer discoverable. Keep Nearby on on both phones and try again.")
            return
        }
        pendingDevices[endpointId] = device
        connections.requestConnection(advertisedEndpointName(), endpointId)
    }

    override fun acceptConnection(endpointId: String) {
        if (!awaitingApproval.remove(endpointId)) return
        approvedEndpoints += endpointId
        connections.acceptConnection(endpointId)
    }

    override fun rejectConnection(endpointId: String) {
        if (endpointId !in pendingDevices && endpointId !in approvedEndpoints) return
        val wasAwaiting = awaitingApproval.remove(endpointId)
        val wasApproved = approvedEndpoints.remove(endpointId)
        pendingDevices.remove(endpointId)
        expectedIdentities.remove(endpointId)
        authenticationTokens.remove(endpointId)
        if (endpointId in establishedEndpoints) {
            connections.disconnect(endpointId)
            onDisconnected(endpointId)
            return
        }
        if (wasAwaiting || wasApproved) connections.rejectConnection(endpointId)
        else connections.disconnect(endpointId)
        listener?.onConnectionClosed(endpointId)
        knownDevices[endpointId]?.let { listener?.onDeviceFound(it) }
    }

    override fun sendMessage(message: OutgoingNearbyMessage) {
        if (eventSession.isEventMode || message.peerId == MeshGroup.ID) return
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
        if (eventSession.isEventMode || group.id == MeshGroup.ID) return
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
        groups.filter { it.id != MeshGroup.ID }.forEach { group ->
            val packet = group.toPacket()
            meshRouter.noteOutgoingGroup(packet)
            sendPacket(endpointId, packet)
        }
        messages.filter { it.conversationId != MeshGroup.ID }.forEach { message ->
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
        if (eventSession.isEventMode || conversationId == MeshGroup.ID) return
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
            if (
                eventId != null &&
                eventSession.eventEndpoints().isEmpty() &&
                advertisingRequested &&
                discoveryRequested
            ) {
                restartForCurrentMode()
            } else {
                broadcastEventPresence(eventId)
            }
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
            eventListener?.onEventMessageSent(message.eventId, message.id)
        }
    }

    override fun refreshEventMesh(eventId: String) {
        if (eventId != eventSession.eventId || !eventSession.accessGranted ||
            !advertisingRequested || !discoveryRequested) return
        // Keep live links and handshakes intact. Advertising cannot restart while connected.
        if (pendingDevices.isEmpty() && establishedEndpoints.isEmpty()) {
            startAdvertisingForCurrentMode()
            startDiscoveryForCurrentMode()
        }
        broadcastEventPresence(eventId, establishedEndpoints.filter(eventSession::containsEndpoint))
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
        onDisconnected(endpointId)
    }

    override fun stop() {
        advertisingRequested = false
        discoveryRequested = false
        connections.stopAdvertising()
        connections.stopDiscovery()
        listener?.let { target -> pendingDevices.keys.toList().forEach(target::onConnectionClosed) }
        connections.disconnectAll()
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        approvedEndpoints.clear()
        awaitingApproval.clear()
        expectedIdentities.clear()
        authenticationTokens.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        deferredEventEndpoints.clear()
        meshRouter.clear()
        // Stopping Nearby pauses the transport; it does not leave the active event.
        // Retaining the identity lets a restart directly resume the same private mesh.
        eventSession.clearEndpoints()
    }

    override fun close() {
        stop()
        eventSession.clear()
        listener = null
        eventListener = null
        connections.listener = null
    }

    override fun onEndpointFound(endpointId: String, endpointName: String) {
        if (!discoveryRequested) return
        if (endpointId in establishedEndpoints || endpointId in pendingDevices) {
            deferredEventEndpoints[endpointId] = endpointName
            return
        }
        deferredEventEndpoints.remove(endpointId)
        val eventPeerId = EventMeshSession.eventPeerIdOrNull(endpointName)
        val isEventDiscovery = eventSession.isEventMode
        if (isEventDiscovery && (eventPeerId == null || eventPeerId == localPeerId)) return
        val device = if (isEventDiscovery) NearbyDevice(endpointId, EventMeshSession.EVENT_ATTENDEE_NAME)
            else NearbyEndpointIdentity.device(endpointId, endpointName)
        knownDevices[endpointId] = device
        if (isEventDiscovery) {
            if (EventMeshSession.shouldInitiateEventConnection(localPeerId, requireNotNull(eventPeerId))) {
                pendingDevices[endpointId] = device
                connections.requestConnection(advertisedEndpointName(), endpointId)
            }
        } else {
            listener?.onDeviceFound(device)
        }
    }

    override fun onEndpointLost(endpointId: String) {
        if (!discoveryRequested) return
        knownDevices.remove(endpointId)
        if (endpointId !in establishedEndpoints && endpointId !in awaitingApproval && endpointId !in approvedEndpoints) {
            deferredEventEndpoints.remove(endpointId)
            val wasPending = pendingDevices.remove(endpointId) != null
            awaitingApproval.remove(endpointId)
            approvedEndpoints.remove(endpointId)
            if (wasPending) {
                connections.rejectConnection(endpointId)
                listener?.onConnectionClosed(endpointId)
            }
        }
        listener?.onDeviceLost(endpointId)
    }

    override fun onConnectionInitiated(endpointId: String, endpointName: String, authenticationDigits: String) {
        if (!advertisingRequested || endpointId in establishedEndpoints ||
            (endpointId !in pendingDevices && pendingDevices.size >= MAX_PENDING_CONNECTIONS)) {
            if (endpointId !in establishedEndpoints) authenticationTokens.remove(endpointId)
            connections.rejectConnection(endpointId)
            return
        }

        val isEventConnection = eventSession.isEventMode
        if (!isEventConnection && authenticationDigits.isBlank()) {
            authenticationTokens.remove(endpointId)
            pendingDevices.remove(endpointId)
            connections.rejectConnection(endpointId)
            listener?.onConnectionClosed(endpointId)
            listener?.onError("Nearby could not provide a verification code. Try connecting again.")
            return
        }
        if (isEventConnection && EventMeshSession.eventPeerIdOrNull(endpointName) == null) {
            authenticationTokens.remove(endpointId)
            connections.rejectConnection(endpointId)
            return
        }
        if (isEventConnection) eventSession.markEndpoint(endpointId)
        val device = if (isEventConnection) NearbyDevice(endpointId, EventMeshSession.EVENT_ATTENDEE_NAME)
            else NearbyEndpointIdentity.device(endpointId, endpointName)
        pendingDevices[endpointId] = device
        knownDevices[endpointId] = device
        if (isEventConnection) {
            approvedEndpoints += endpointId
            connections.acceptConnection(endpointId)
        } else if (awaitingApproval.add(endpointId)) {
            listener?.onConnectionInitiated(device, authenticationDigits)
        }
    }

    override fun onConnectionInitiated(endpointId: String, endpointName: String, authenticationDigits: String,
        rawToken: ByteArray) {
        authenticationTokens[endpointId] = rawToken.copyOf()
        onConnectionInitiated(endpointId, endpointName, authenticationDigits)
    }

    override fun onConnectionSucceeded(endpointId: String) {
        if (!advertisingRequested || endpointId !in approvedEndpoints) {
            expectedIdentities.remove(endpointId)
            authenticationTokens.remove(endpointId)
            val wasPending = pendingDevices.remove(endpointId) != null
            awaitingApproval.remove(endpointId)
            if (wasPending) listener?.onConnectionClosed(endpointId)
            connections.disconnect(endpointId)
            return
        }
        establishedEndpoints += endpointId
        pendingDevices.remove(endpointId)
        val token = authenticationTokens[endpointId]
        val hello = if (!eventSession.isEventMode && token != null && token.isNotEmpty() && localUsername.isNotBlank() && localAccountUid.isNotBlank()) {
            val keys = identities.keys()
            val unsigned = NearbyPacket.Hello(localPeerId, localDisplayName, localPhoneHash, localUsername,
                localAccountUid, NearbyIdentityProof.publicKey(keys))
            unsigned.copy(signature = NearbyIdentityProof.sign(unsigned, token, keys))
        } else NearbyPacket.Hello(localPeerId, localDisplayName, localPhoneHash)
        sendPacket(endpointId, hello)
    }

    override fun onConnectionFailed(endpointId: String, message: String) {
        if (!advertisingRequested) return
        if (endpointId !in pendingDevices && endpointId !in approvedEndpoints) return
        connections.disconnect(endpointId)
        if (endpointId in establishedEndpoints || endpointId in peerByEndpoint) {
            onDisconnected(endpointId)
            listener?.onError(message)
            return
        }
        pendingDevices.remove(endpointId)
        awaitingApproval.remove(endpointId)
        approvedEndpoints.remove(endpointId)
        expectedIdentities.remove(endpointId)
        authenticationTokens.remove(endpointId)
        eventSession.removeEndpoint(endpointId)
        listener?.onConnectionClosed(endpointId)
        if (!eventSession.isEventMode) knownDevices[endpointId]?.let { listener?.onDeviceFound(it) }
        listener?.onError(message)
        resumeDeferredEventEndpoint(endpointId)
        recoverIdleTransport()
    }

    override fun onDisconnected(endpointId: String) {
        if (!advertisingRequested) return
        if (endpointId !in establishedEndpoints && endpointId !in pendingDevices && endpointId !in peerByEndpoint) return
        establishedEndpoints.remove(endpointId)
        expectedIdentities.remove(endpointId)
        authenticationTokens.remove(endpointId)
        approvedEndpoints.remove(endpointId)
        awaitingApproval.remove(endpointId)
        pendingDevices.remove(endpointId)
        listener?.onConnectionClosed(endpointId)
        val wasEventConnection = eventSession.removeEndpoint(endpointId)
        peerByEndpoint.remove(endpointId)?.let { peer ->
            if (endpointByPeer[peer.peerId] == endpointId) {
                endpointByPeer.remove(peer.peerId)
                if (!wasEventConnection) listener?.onDisconnected(peer.peerId)
            }
        }
        resumeDeferredEventEndpoint(endpointId)
        if (!eventSession.isEventMode) {
            knownDevices[endpointId]?.let { listener?.onDeviceFound(it) }
        }
        recoverIdleTransport()
    }

    private fun recoverIdleTransport() {
        if (advertisingRequested && discoveryRequested && establishedEndpoints.isEmpty() && pendingDevices.isEmpty()) {
            // Clear the SDK's old endpoint traces, not just the UI's connection flag.
            restartForCurrentMode()
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
        if (packet.peerId.isBlank() || packet.name.isBlank() || packet.peerId == localPeerId || packet.peerId == MeshGroup.ID) return

        val expected = expectedIdentities[endpointId]
        val signed = packet.username.isNotBlank() && !eventSession.isEventMode
        val validProof = signed && NearbyIdentityProof.verify(packet, authenticationTokens[endpointId] ?: byteArrayOf())
        val pinned = expected ?: identities.trusted(packet.peerId)
        if (!eventSession.isEventMode && ((signed && !validProof) ||
                (pinned != null && (!validProof || !NearbyIdentityProof.matches(packet, pinned))))) {
            connections.disconnect(endpointId)
            onDisconnected(endpointId)
            listener?.onError("Nearby device identity did not match the saved contact. The connection was closed.")
            return
        }

        val oldEndpoint = endpointByPeer.put(packet.peerId, endpointId)
        if (oldEndpoint != null && oldEndpoint != endpointId) {
            connections.disconnect(oldEndpoint)
        }

        val peer = ConnectedPeer(packet.peerId, endpointId, packet.name.take(24), packet.phoneHash,
            if (validProof) packet.username else "", if (validProof) packet.accountUid else "",
            if (validProof) packet.publicKey else "", pinned?.accountVerified == true)
        peerByEndpoint[endpointId] = peer
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
        listener?.let { target -> pendingDevices.keys.toList().forEach(target::onConnectionClosed) }
        connections.disconnectAll()
        listener?.let { target -> knownDevices.keys.toList().forEach(target::onDeviceLost) }
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        approvedEndpoints.clear()
        awaitingApproval.clear()
        expectedIdentities.clear()
        authenticationTokens.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        deferredEventEndpoints.clear()
        eventSession.clearEndpoints()
        meshRouter.resetKnownPeers()
        if (localPeerId.isNotBlank()) {
            meshRouter.rememberPeer(GroupMember(localPeerId, localDisplayName, localPhoneHash))
        }
        normalPeers.forEach { peerId -> listener?.onDisconnected(peerId) }
        if (localPeerId.isBlank()) return
        if (advertisingRequested) startAdvertisingForCurrentMode()
        if (discoveryRequested) startDiscoveryForCurrentMode()
    }

    private fun resumeDeferredEventEndpoint(endpointId: String) {
        val endpointName = deferredEventEndpoints.remove(endpointId) ?: return
        if (!advertisingRequested || !discoveryRequested) return
        onEndpointFound(endpointId, endpointName)
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

    private fun advertisedEndpointName(): String = if (!eventSession.isEventMode &&
        localUsername.matches(Regex("[a-z0-9_]{3,20}")))
        NearbyEndpointIdentity.name(localPeerId, localUsername, localDisplayName)
        else eventSession.advertisedEndpointName(localPeerId, localDisplayName)

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
                is MeshLocalDelivery.EventPeer -> eventListener?.onEventPeerAvailable(
                    delivery.peerId,
                    delivery.eventId,
                    delivery.userId,
                    delivery.accessGranted,
                )
                is MeshLocalDelivery.EventChat -> eventListener?.onEventChatMessageReceived(delivery.message)
                is MeshLocalDelivery.IncomingEventAnnouncement ->
                    eventListener?.onEventAnnouncementReceived(delivery.announcement)
                is MeshLocalDelivery.IncomingEventMutation ->
                    eventListener?.onEventMutationReceived(delivery.mutation)
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

    private fun PrivateGroup.toPacket() = NearbyPacket.GroupDefinition(
        groupId = id,
        name = name,
        ownerId = ownerId,
        createdAt = createdAt,
        members = members,
        hopsRemaining = MeshRouter.MAX_HOPS,
    )

    internal companion object {
        private const val MAX_PENDING_CONNECTIONS = 8
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
