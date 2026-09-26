package com.example.blap.chat

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventMutation
import com.example.blap.event.EventAccessGrant
import com.example.blap.event.EventAccessRequest
import com.example.blap.event.EventVisibility
import java.security.MessageDigest

class NearbyChatManager(context: Context) : NearbyChatController {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val knownDevices = mutableMapOf<String, NearbyDevice>()
    private val pendingDevices = mutableMapOf<String, NearbyDevice>()
    private val establishedEndpoints = mutableSetOf<String>()
    private val peerByEndpoint = mutableMapOf<String, ConnectedPeer>()
    private val endpointByPeer = mutableMapOf<String, String>()
    private val seenMessageIds = boundedIdSet()
    private val seenAcknowledgements = boundedIdSet()
    private val seenGroupDefinitions = boundedIdSet()
    private val seenEventMessageIds = boundedIdSet()
    private val seenEventAnnouncementIds = boundedIdSet()
    private val seenEventMutationIds = boundedIdSet()
    private val seenEventAccessRequestIds = boundedIdSet()
    private val seenEventAccessGrantIds = boundedIdSet()
    private val knownMeshPeers = mutableMapOf<String, GroupMember>()
    private val cachedGroupDefinitions = linkedMapOf<String, NearbyPacket.GroupDefinition>()
    private val cachedGroupMessages = linkedMapOf<String, NearbyPacket.Message>()
    private val eventEndpoints = mutableSetOf<String>()

    private var localDisplayName = ""
    private var localPeerId = ""
    private var localPhoneHash = ""
    private var activeEventId: String? = null
    private var activeEventMeshSecret = ""
    private var activeEventUserId = ""
    private var activeEventAccessGranted = false
    private var advertisingRequested = false
    private var discoveryRequested = false

    override var listener: NearbyChatController.Listener? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId !in establishedEndpoints || payload.type != Payload.Type.BYTES) return
            val packet = payload.asBytes()?.let(NearbyProtocol::decode) ?: return

            when (packet) {
                is NearbyPacket.Hello -> handleHello(endpointId, packet)
                is NearbyPacket.Message -> handleMessage(endpointId, packet)
                is NearbyPacket.Acknowledgement -> handleAcknowledgement(endpointId, packet)
                is NearbyPacket.GroupDefinition -> handleGroupDefinition(endpointId, packet)
                is NearbyPacket.PeerAnnouncement -> handlePeerAnnouncement(endpointId, packet)
                is NearbyPacket.EventPresence -> handleEventPresence(endpointId, packet)
                is NearbyPacket.EventChatMessage -> handleEventChatMessage(endpointId, packet)
                is NearbyPacket.EventAnnouncement -> handleEventAnnouncement(endpointId, packet)
                is NearbyPacket.EventMutation -> handleEventMutation(endpointId, packet)
                is NearbyPacket.EventAccessRequest -> handleEventAccessRequest(endpointId, packet)
                is NearbyPacket.EventAccessGrant -> handleEventAccessGrant(endpointId, packet)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (endpointId in establishedEndpoints) {
                connectionsClient.rejectConnection(endpointId)
                return
            }

            val isEventConnection = activeEventId != null
            if (isEventConnection) eventEndpoints += endpointId
            val device = NearbyDevice(
                endpointId,
                if (isEventConnection) EVENT_ATTENDEE_NAME else info.endpointName,
            )
            pendingDevices[endpointId] = device
            if (!isEventConnection) {
                listener?.onConnectionInitiated(device, info.authenticationDigits)
            }
            acceptConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    establishedEndpoints += endpointId
                    pendingDevices.remove(endpointId)
                    knownDevices.remove(endpointId)
                    sendPacket(endpointId, NearbyPacket.Hello(localPeerId, localDisplayName, localPhoneHash))
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    pendingDevices.remove(endpointId)
                    listener?.onError("The other phone declined the connection.")
                }

                else -> {
                    pendingDevices.remove(endpointId)
                    listener?.onError(connectionFailureMessage(result.status.statusCode))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            establishedEndpoints.remove(endpointId)
            pendingDevices.remove(endpointId)
            val wasEventConnection = eventEndpoints.remove(endpointId)
            val peer = peerByEndpoint.remove(endpointId) ?: return
            if (endpointByPeer[peer.peerId] == endpointId) {
                endpointByPeer.remove(peer.peerId)
                if (!wasEventConnection) listener?.onDisconnected(peer.peerId)
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (endpointId in establishedEndpoints || endpointId in pendingDevices) return
            val eventPeerId = info.endpointName.eventPeerIdOrNull()
            val isEventDiscovery = activeEventId != null
            if (isEventDiscovery && (eventPeerId == null || eventPeerId == localPeerId)) return
            val device = NearbyDevice(
                endpointId,
                if (isEventDiscovery) EVENT_ATTENDEE_NAME else info.endpointName,
            )
            knownDevices[endpointId] = device
            if (isEventDiscovery) {
                if (shouldInitiateEventConnection(localPeerId, requireNotNull(eventPeerId))) {
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
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(displayName: String, peerId: String, phoneHash: String) {
        localDisplayName = displayName
        localPeerId = peerId
        localPhoneHash = phoneHash
        knownMeshPeers[peerId] = GroupMember(peerId, displayName, phoneHash)
        advertisingRequested = true
        startAdvertisingForCurrentMode()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingForCurrentMode() {
        connectionsClient.stopAdvertising()

        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startAdvertising(
                advertisedEndpointName(),
                currentServiceId(),
                connectionLifecycleCallback,
                options,
            ).addOnFailureListener { exception ->
                listener?.onNearbyUnavailable("Could not turn on nearby messaging: ${exception.readableMessage()}")
            }
        } catch (_: SecurityException) {
            listener?.onNearbyUnavailable("Allow nearby permissions to make this phone discoverable.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        discoveryRequested = true
        startDiscoveryForCurrentMode()
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryForCurrentMode() {
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startDiscovery(currentServiceId(), endpointDiscoveryCallback, options)
                .addOnFailureListener { exception ->
                    listener?.onNearbyUnavailable("Could not find nearby phones: ${exception.readableMessage()}")
                }
        } catch (_: SecurityException) {
            listener?.onNearbyUnavailable("Allow nearby permissions to find other phones.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToDevice(endpointId: String) {
        if (endpointId in establishedEndpoints || endpointId in pendingDevices) return
        val device = knownDevices[endpointId] ?: return
        pendingDevices[endpointId] = device

        try {
            connectionsClient.requestConnection(
                localDisplayName,
                endpointId,
                connectionLifecycleCallback,
            ).addOnFailureListener { exception ->
                pendingDevices.remove(endpointId)
                listener?.onError(exception.connectionFailureMessage("Connection request"))
            }
        } catch (_: SecurityException) {
            pendingDevices.remove(endpointId)
            listener?.onError("Nearby permissions are required to connect.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendMessage(message: OutgoingNearbyMessage) {
        if (activeEventId != null) return
        val packet = NearbyPacket.Message(
            messageId = message.messageId,
            senderId = localPeerId,
            senderName = localDisplayName,
            senderPhoneHash = localPhoneHash,
            recipientId = message.peerId,
            sentAt = message.sentAt,
            text = message.text,
            hopsRemaining = MAX_HOPS,
            isGroup = message.isGroup,
        )

        if (message.isGroup) {
            rememberId(seenMessageIds, message.messageId)
            rememberGroupMessage(packet)
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
        if (activeEventId != null) return
        val packet = group.toPacket()
        rememberId(seenGroupDefinitions, groupDefinitionId(packet))
        cachedGroupDefinitions[group.id] = packet
        sendPacketToMany(establishedEndpoints, packet)
    }

    override fun synchronizeGroups(
        peerId: String,
        groups: List<PrivateGroup>,
        messages: List<StoredGroupMessage>,
    ) {
        if (activeEventId != null) return
        val endpointId = endpointByPeer[peerId] ?: return
        groups.forEach { group ->
            val packet = group.toPacket()
            rememberId(seenGroupDefinitions, groupDefinitionId(packet))
            cachedGroupDefinitions[group.id] = packet
            sendPacket(endpointId, packet)
        }
        messages.forEach { message ->
            rememberId(seenMessageIds, message.messageId)
            val packet = NearbyPacket.Message(
                messageId = message.messageId,
                senderId = message.senderId,
                senderName = message.senderName,
                senderPhoneHash = message.senderPhoneHash,
                recipientId = message.conversationId,
                sentAt = message.sentAt,
                text = message.text,
                hopsRemaining = MAX_HOPS,
                isGroup = true,
            )
            rememberGroupMessage(packet)
            sendPacketWithResult(endpointId, packet) {
                listener?.onMessageSent(message.conversationId, message.messageId)
            }
        }
    }

    override fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String) {
        if (activeEventId != null) return
        val packet = NearbyPacket.Acknowledgement(
            messageId = messageId,
            senderId = localPeerId,
            recipientId = senderId,
            conversationId = conversationId,
            hopsRemaining = MAX_HOPS,
            isGroup = conversationId != senderId,
        )
        if (packet.isGroup) {
            rememberId(seenAcknowledgements, acknowledgementId(packet))
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
        if (eventId == activeEventId && meshSecret == activeEventMeshSecret) {
            activeEventUserId = userId
            activeEventAccessGranted = accessGranted
            broadcastEventPresence(eventId)
            return
        }
        broadcastEventPresence(null)
        activeEventId = eventId
        activeEventMeshSecret = meshSecret
        activeEventUserId = userId
        activeEventAccessGranted = accessGranted && eventId != null
        restartForCurrentMode()
    }

    override fun sendEventChatMessage(message: EventChatMessage) {
        if (!activeEventAccessGranted || message.eventId != activeEventId || message.text.isBlank()) return
        val packet = message.toPacket()
        rememberId(seenEventMessageIds, message.id)
        sendPacketToMany(eventEndpoints, packet) {
            listener?.onEventMessageSent(message.eventId, message.id)
        }
    }

    override fun sendEventAnnouncement(announcement: EventAnnouncement) {
        if (!activeEventAccessGranted || announcement.eventId != activeEventId) return
        val packet = announcement.toPacket()
        rememberId(seenEventAnnouncementIds, eventAnnouncementKey(packet))
        sendPacketToMany(eventEndpoints, packet)
    }

    override fun sendEventMutation(mutation: EventMutation) {
        if (!activeEventAccessGranted || mutation.event.id != activeEventId) return
        val packet = mutation.toPacket()
        rememberId(seenEventMutationIds, eventMutationKey(packet))
        sendPacketToMany(eventEndpoints, packet)
    }

    override fun sendEventAccessRequest(request: EventAccessRequest) {
        if (request.eventId != activeEventId || request.userId != activeEventUserId) return
        val packet = request.toPacket()
        rememberId(seenEventAccessRequestIds, request.id)
        sendPacketToMany(eventEndpoints, packet)
    }

    override fun sendEventAccessGrant(grant: EventAccessGrant) {
        if (!activeEventAccessGranted || grant.eventId != activeEventId) return
        val packet = grant.toPacket()
        rememberId(seenEventAccessGrantIds, grant.id)
        sendPacketToMany(eventEndpoints, packet)
    }

    override fun synchronizeEventHistory(peerId: String, messages: List<EventChatMessage>) {
        val endpointId = endpointByPeer[peerId] ?: return
        messages.takeLast(MAX_EVENT_HISTORY).forEach { message ->
            rememberId(seenEventMessageIds, message.id)
            sendPacket(endpointId, message.toPacket())
        }
    }

    override fun synchronizeEventAnnouncements(
        peerId: String,
        announcements: List<EventAnnouncement>,
    ) {
        val eventId = activeEventId ?: return
        val endpointId = endpointByPeer[peerId]?.takeIf(eventEndpoints::contains) ?: return
        announcements
            .filter { announcement ->
                announcement.eventId == eventId && announcement.signature.isNotBlank()
            }
            .takeLast(MAX_EVENT_ANNOUNCEMENT_HISTORY)
            .forEach { announcement ->
                val packet = announcement.toPacket()
                rememberId(seenEventAnnouncementIds, eventAnnouncementKey(packet))
                sendPacket(endpointId, packet)
            }
    }

    override fun disconnect(peerId: String) {
        val endpointId = endpointByPeer[peerId] ?: return
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    override fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        seenMessageIds.clear()
        seenAcknowledgements.clear()
        seenGroupDefinitions.clear()
        seenEventMessageIds.clear()
        seenEventAnnouncementIds.clear()
        seenEventMutationIds.clear()
        seenEventAccessRequestIds.clear()
        seenEventAccessGrantIds.clear()
        knownMeshPeers.clear()
        cachedGroupDefinitions.clear()
        cachedGroupMessages.clear()
        eventEndpoints.clear()
        activeEventId = null
        activeEventMeshSecret = ""
        activeEventUserId = ""
        activeEventAccessGranted = false
        advertisingRequested = false
        discoveryRequested = false
    }

    override fun close() {
        stop()
        listener = null
    }

    private fun handleHello(endpointId: String, packet: NearbyPacket.Hello) {
        if (packet.peerId.isBlank() || packet.name.isBlank() || packet.peerId == localPeerId) return

        val oldEndpoint = endpointByPeer.put(packet.peerId, endpointId)
        if (oldEndpoint != null && oldEndpoint != endpointId) {
            connectionsClient.disconnectFromEndpoint(oldEndpoint)
        }

        val peer = ConnectedPeer(packet.peerId, endpointId, packet.name.take(24), packet.phoneHash)
        peerByEndpoint[endpointId] = peer
        knownDevices.remove(endpointId)
        if (endpointId in eventEndpoints || activeEventId != null) {
            eventEndpoints += endpointId
            broadcastEventPresence(activeEventId, listOf(endpointId))
            return
        }

        knownMeshPeers[peer.peerId] = GroupMember(peer.peerId, peer.name, peer.phoneHash)
        listener?.onConnected(peer)

        knownMeshPeers.values.forEach { knownPeer ->
            sendPacket(
                endpointId,
                NearbyPacket.PeerAnnouncement(
                    peerId = knownPeer.peerId,
                    name = knownPeer.name,
                    phoneHash = knownPeer.phoneHash,
                    hopsRemaining = MAX_HOPS,
                ),
            )
        }
        sendPacketToMany(
            establishedEndpoints - endpointId,
            NearbyPacket.PeerAnnouncement(peer.peerId, peer.name, peer.phoneHash, MAX_HOPS),
        )
        cachedGroupDefinitions.values.forEach { definition ->
            sendPacket(endpointId, definition.copy(hopsRemaining = MAX_HOPS))
        }
        cachedGroupMessages.values.forEach { message ->
            sendPacket(endpointId, message.copy(hopsRemaining = MAX_HOPS))
        }
    }

    private fun restartForCurrentMode() {
        val normalPeers = peerByEndpoint
            .filterKeys { endpointId -> endpointId !in eventEndpoints }
            .values
            .map(ConnectedPeer::peerId)
            .distinct()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        knownDevices.clear()
        pendingDevices.clear()
        establishedEndpoints.clear()
        peerByEndpoint.clear()
        endpointByPeer.clear()
        eventEndpoints.clear()
        knownMeshPeers.clear()
        if (localPeerId.isNotBlank()) {
            knownMeshPeers[localPeerId] = GroupMember(localPeerId, localDisplayName, localPhoneHash)
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
            NearbyPacket.EventPresence(
                eventId = eventId.orEmpty(),
                peerId = localPeerId,
                name = localDisplayName,
                userId = activeEventUserId,
                active = eventId != null,
                accessGranted = activeEventAccessGranted,
            ),
        )
    }

    private fun currentServiceId(): String = activeEventId
        ?.let { eventServiceId(it, activeEventMeshSecret) }
        ?: SERVICE_ID

    private fun advertisedEndpointName(): String = if (activeEventId == null) {
        localDisplayName
    } else {
        "$EVENT_ENDPOINT_PREFIX$localPeerId"
    }

    private fun handleMessage(endpointId: String, packet: NearbyPacket.Message) {
        if (endpointId in eventEndpoints) return
        val peer = peerByEndpoint[endpointId] ?: return
        if (packet.messageId.isBlank() || packet.text.isBlank()) return
        if (packet.hopsRemaining !in 0..MAX_HOPS) return

        if (packet.isGroup) {
            val firstSeen = rememberId(seenMessageIds, packet.messageId)
            if (firstSeen) rememberGroupMessage(packet)
            listener?.onMessageReceived(
                IncomingNearbyMessage(
                    messageId = packet.messageId,
                    conversationId = packet.recipientId,
                    senderId = packet.senderId,
                    senderName = packet.senderName.take(24),
                    senderPhoneHash = packet.senderPhoneHash,
                    text = packet.text.take(1_000),
                    sentAt = packet.sentAt,
                ),
            )
            if (firstSeen && packet.hopsRemaining > 0) {
                sendPacketToMany(
                    establishedEndpoints - endpointId,
                    packet.copy(hopsRemaining = packet.hopsRemaining - 1),
                )
            }
            return
        }

        if (packet.senderId != peer.peerId || packet.recipientId != localPeerId) return

        listener?.onMessageReceived(
            IncomingNearbyMessage(
                messageId = packet.messageId,
                conversationId = packet.senderId,
                senderId = packet.senderId,
                senderName = peer.name,
                senderPhoneHash = peer.phoneHash,
                text = packet.text.take(1_000),
                sentAt = packet.sentAt,
            ),
        )
    }

    private fun handleAcknowledgement(endpointId: String, packet: NearbyPacket.Acknowledgement) {
        if (endpointId in eventEndpoints) return
        val peer = peerByEndpoint[endpointId] ?: return
        if (packet.isGroup) {
            if (packet.hopsRemaining !in 0..MAX_HOPS) return
            if (!rememberId(seenAcknowledgements, acknowledgementId(packet))) return
            if (packet.recipientId == localPeerId) {
                listener?.onMessageDelivered(packet.conversationId, packet.messageId)
            }
            if (packet.hopsRemaining > 0) {
                sendPacketToMany(
                    establishedEndpoints - endpointId,
                    packet.copy(hopsRemaining = packet.hopsRemaining - 1),
                )
            }
            return
        }

        if (packet.senderId != peer.peerId || packet.recipientId != localPeerId) return
        listener?.onMessageDelivered(packet.conversationId, packet.messageId)
    }

    private fun handleGroupDefinition(endpointId: String, packet: NearbyPacket.GroupDefinition) {
        if (endpointId in eventEndpoints) return
        if (peerByEndpoint[endpointId] == null) return
        if (packet.groupId.isBlank() || packet.name.isBlank() || packet.ownerId.isBlank()) return
        if (packet.members.isEmpty() || packet.members.size > MAX_GROUP_MEMBERS) return
        if (packet.hopsRemaining !in 0..MAX_HOPS) return
        if (packet.members.any { it.peerId.isBlank() || it.name.isBlank() }) return
        if (!rememberId(seenGroupDefinitions, groupDefinitionId(packet))) return
        cachedGroupDefinitions[packet.groupId] = packet.copy(hopsRemaining = MAX_HOPS)

        listener?.onGroupReceived(
            PrivateGroup(
                id = packet.groupId,
                name = packet.name.take(40),
                ownerId = packet.ownerId,
                createdAt = packet.createdAt,
                members = packet.members.map { it.copy(name = it.name.take(24)) },
            ),
        )
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                establishedEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handlePeerAnnouncement(endpointId: String, packet: NearbyPacket.PeerAnnouncement) {
        if (endpointId in eventEndpoints) return
        if (peerByEndpoint[endpointId] == null) return
        if (packet.peerId.isBlank() || packet.name.isBlank() || packet.peerId == localPeerId) return
        if (packet.hopsRemaining !in 0..MAX_HOPS) return
        val peer = GroupMember(packet.peerId, packet.name.take(24), packet.phoneHash)
        if (knownMeshPeers.put(peer.peerId, peer) == peer) return

        listener?.onMeshPeerFound(peer)
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                establishedEndpoints - endpointId,
                packet.copy(name = peer.name, phoneHash = peer.phoneHash, hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handleEventPresence(endpointId: String, packet: NearbyPacket.EventPresence) {
        if (endpointId !in eventEndpoints) return
        val peer = peerByEndpoint[endpointId] ?: return
        if (!packet.active || packet.eventId.isBlank()) return
        if (packet.peerId != peer.peerId) return
        if (packet.eventId == activeEventId) {
            listener?.onEventPeerAvailable(peer.peerId, packet.eventId, packet.userId, packet.accessGranted)
        }
    }

    private fun handleEventChatMessage(endpointId: String, packet: NearbyPacket.EventChatMessage) {
        if (!activeEventAccessGranted) return
        if (endpointId !in eventEndpoints) return
        if (peerByEndpoint[endpointId] == null) return
        if (packet.messageId.isBlank() || packet.eventId.isBlank() || packet.senderId.isBlank() ||
            packet.senderName.isBlank() || packet.text.isBlank()
        ) return
        if (packet.hopsRemaining !in 0..MAX_HOPS) return
        if (packet.eventId != activeEventId) return
        if (!rememberId(seenEventMessageIds, packet.messageId)) return
        listener?.onEventChatMessageReceived(
            EventChatMessage(
                id = packet.messageId,
                eventId = packet.eventId,
                senderId = packet.senderId,
                senderName = packet.senderName.take(24),
                text = packet.text.take(MAX_EVENT_MESSAGE_LENGTH),
                createdAt = packet.sentAt,
            ),
        )
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                eventEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handleEventAnnouncement(endpointId: String, packet: NearbyPacket.EventAnnouncement) {
        if (!activeEventAccessGranted) return
        if (endpointId !in eventEndpoints) return
        if (peerByEndpoint[endpointId] == null) return
        if (packet.announcementId.isBlank() || packet.eventId.isBlank() || packet.adminId.isBlank() ||
            packet.adminName.isBlank() || packet.text.isBlank()
        ) return
        if (packet.hopsRemaining !in 0..MAX_HOPS) return
        if (packet.eventId != activeEventId) return
        if (!rememberId(seenEventAnnouncementIds, eventAnnouncementKey(packet))) return
        listener?.onEventAnnouncementReceived(
            EventAnnouncement(
                id = packet.announcementId,
                eventId = packet.eventId,
                adminId = packet.adminId,
                adminName = packet.adminName.take(24),
                text = packet.text.take(MAX_EVENT_MESSAGE_LENGTH),
                createdAt = packet.createdAt,
                revision = packet.revision,
                signature = packet.signature,
                syncedToCloud = false,
            ),
        )
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                eventEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handleEventMutation(endpointId: String, packet: NearbyPacket.EventMutation) {
        if (!activeEventAccessGranted) return
        if (endpointId !in eventEndpoints) return
        if (peerByEndpoint[endpointId] == null) return
        if (packet.eventId.isBlank() || packet.adminId.isBlank() || packet.title.isBlank() ||
            packet.createdBy.isBlank() || packet.signature.isBlank()
        ) return
        if (packet.hopsRemaining !in 0..MAX_HOPS || packet.eventId != activeEventId) return
        if (!rememberId(seenEventMutationIds, eventMutationKey(packet))) return
        val event = runCatching {
            CommunityEvent(
                id = packet.eventId,
                title = packet.title.take(80),
                description = packet.description.take(1_000),
                venueName = packet.venueName.take(200),
                latitude = packet.latitude,
                longitude = packet.longitude,
                radiusMetres = packet.radiusMetres,
                startsAt = packet.startsAt,
                endsAt = packet.endsAt,
                createdBy = packet.createdBy,
                adminIds = packet.adminIds.toSet(),
                memberIds = packet.adminIds.toSet(),
                adminPublicKeys = packet.adminPublicKeys,
                visibility = EventVisibility.valueOf(packet.visibility),
                requiresSignIn = packet.requiresSignIn,
                privateMeshSecret = if (packet.visibility == EventVisibility.PRIVATE.name) {
                    "pending-verification"
                } else "",
                createdAt = packet.createdAt,
                updatedAt = packet.updatedAt,
                deletedAt = packet.deletedAt,
            )
        }.getOrNull() ?: return
        listener?.onEventMutationReceived(EventMutation(event, packet.adminId, packet.signature))
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                eventEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handleEventAccessRequest(endpointId: String, packet: NearbyPacket.EventAccessRequest) {
        if (endpointId !in eventEndpoints || peerByEndpoint[endpointId] == null) return
        if (packet.requestId.isBlank() || packet.eventId != activeEventId || packet.userId.isBlank() ||
            packet.peerId.isBlank() || packet.hopsRemaining !in 0..MAX_HOPS
        ) return
        if (!rememberId(seenEventAccessRequestIds, packet.requestId)) return
        listener?.onEventAccessRequestReceived(
            EventAccessRequest(
                id = packet.requestId,
                eventId = packet.eventId,
                userId = packet.userId,
                peerId = packet.peerId,
                displayName = packet.displayName.take(24),
                requestedAt = packet.requestedAt,
            ),
        )
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                eventEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun handleEventAccessGrant(endpointId: String, packet: NearbyPacket.EventAccessGrant) {
        if (endpointId !in eventEndpoints || peerByEndpoint[endpointId] == null) return
        if (packet.grantId.isBlank() || packet.requestId.isBlank() || packet.eventId != activeEventId ||
            packet.userId.isBlank() || packet.adminId.isBlank() || packet.signature.isBlank() ||
            packet.hopsRemaining !in 0..MAX_HOPS
        ) return
        if (!rememberId(seenEventAccessGrantIds, packet.grantId)) return
        listener?.onEventAccessGrantReceived(
            EventAccessGrant(
                id = packet.grantId,
                requestId = packet.requestId,
                eventId = packet.eventId,
                userId = packet.userId,
                peerId = packet.peerId,
                adminId = packet.adminId,
                issuedAt = packet.issuedAt,
                expiresAt = packet.expiresAt,
                signature = packet.signature,
            ),
        )
        if (packet.hopsRemaining > 0) {
            sendPacketToMany(
                eventEndpoints - endpointId,
                packet.copy(hopsRemaining = packet.hopsRemaining - 1),
            )
        }
    }

    private fun sendPacket(endpointId: String, packet: NearbyPacket) {
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(NearbyProtocol.encode(packet)))
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to use this connection.")
        }
    }

    private fun sendPacketWithResult(endpointId: String, packet: NearbyPacket, onSuccess: () -> Unit) {
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(NearbyProtocol.encode(packet)))
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { exception ->
                    listener?.onError(exception.connectionFailureMessage("Message"))
                }
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to send messages.")
        }
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

    private fun rememberId(ids: LinkedHashSet<String>, id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > MAX_REMEMBERED_IDS) ids.remove(ids.first())
        return true
    }

    private fun acknowledgementId(packet: NearbyPacket.Acknowledgement): String =
        "${packet.messageId}:${packet.senderId}"

    private fun groupDefinitionId(packet: NearbyPacket.GroupDefinition): String =
        "${packet.groupId}:${packet.createdAt}"

    private fun rememberGroupMessage(packet: NearbyPacket.Message) {
        cachedGroupMessages[packet.messageId] = packet.copy(hopsRemaining = MAX_HOPS)
        if (cachedGroupMessages.size > MAX_CACHED_GROUP_MESSAGES) {
            cachedGroupMessages.remove(cachedGroupMessages.keys.first())
        }
    }

    private fun EventChatMessage.toPacket() = NearbyPacket.EventChatMessage(
        messageId = id,
        eventId = eventId,
        senderId = senderId,
        senderName = senderName,
        text = text.take(MAX_EVENT_MESSAGE_LENGTH),
        sentAt = createdAt,
        hopsRemaining = MAX_HOPS,
    )

    private fun EventAnnouncement.toPacket() = NearbyPacket.EventAnnouncement(
        announcementId = id,
        eventId = eventId,
        adminId = adminId,
        adminName = adminName,
        text = text.take(MAX_EVENT_MESSAGE_LENGTH),
        createdAt = createdAt,
        revision = revision,
        signature = signature,
        hopsRemaining = MAX_HOPS,
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
        createdAt = event.createdAt,
        updatedAt = event.updatedAt,
        deletedAt = event.deletedAt,
        signature = signature,
        hopsRemaining = MAX_HOPS,
    )

    private fun EventAccessRequest.toPacket() = NearbyPacket.EventAccessRequest(
        requestId = id,
        eventId = eventId,
        userId = userId,
        peerId = peerId,
        displayName = displayName,
        requestedAt = requestedAt,
        hopsRemaining = MAX_HOPS,
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
        hopsRemaining = MAX_HOPS,
    )

    private fun eventAnnouncementKey(packet: NearbyPacket.EventAnnouncement): String =
        "${packet.announcementId}:${packet.revision}"

    private fun eventMutationKey(packet: NearbyPacket.EventMutation): String =
        "${packet.eventId}:${packet.updatedAt}"

    private fun PrivateGroup.toPacket() = NearbyPacket.GroupDefinition(
        groupId = id,
        name = name,
        ownerId = ownerId,
        createdAt = createdAt,
        members = members,
        hopsRemaining = MAX_HOPS,
    )

    @SuppressLint("MissingPermission")
    private fun acceptConnection(endpointId: String) {
        try {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { exception ->
                    pendingDevices.remove(endpointId)
                    listener?.onError(exception.connectionFailureMessage("Accepting the connection"))
                }
        } catch (_: SecurityException) {
            pendingDevices.remove(endpointId)
            listener?.onError("Nearby permissions are required to accept a connection.")
        }
    }

    private fun Exception.readableMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "unknown Nearby error"

    private fun Exception.connectionFailureMessage(action: String): String {
        val statusCode = (this as? ApiException)?.statusCode
        return if (statusCode == null) "$action failed: ${readableMessage()}"
        else connectionFailureMessage(statusCode)
    }

    private fun connectionFailureMessage(statusCode: Int): String = when (statusCode) {
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR ->
            "The direct link dropped. Keep Wi-Fi and Bluetooth on and try again."

        ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
            "A phone radio could not start the link. Toggle Wi-Fi and Bluetooth and try again."

        else -> "The connection failed (${ConnectionsStatusCodes.getStatusCodeString(statusCode)})."
    }

    internal companion object {
        const val SERVICE_ID = "com.example.blap"
        const val MAX_HOPS = 16
        const val MAX_REMEMBERED_IDS = 10_000
        const val MAX_GROUP_MEMBERS = 100
        const val MAX_CACHED_GROUP_MESSAGES = 2_000
        const val MAX_EVENT_HISTORY = 50
        const val MAX_EVENT_ANNOUNCEMENT_HISTORY = 100
        const val MAX_EVENT_MESSAGE_LENGTH = 1_000
        const val EVENT_ENDPOINT_PREFIX = "cg-event|"
        const val EVENT_ATTENDEE_NAME = "Event attendee"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        fun boundedIdSet() = LinkedHashSet<String>()

        fun eventServiceId(eventId: String, meshSecret: String = ""): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$eventId|$meshSecret".toByteArray())
            val token = digest.take(EVENT_SERVICE_HASH_BYTES)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "$SERVICE_ID.event.$token"
        }

        fun String.eventPeerIdOrNull(): String? = takeIf { startsWith(EVENT_ENDPOINT_PREFIX) }
            ?.removePrefix(EVENT_ENDPOINT_PREFIX)
            ?.takeIf(String::isNotBlank)

        fun shouldInitiateEventConnection(localPeerId: String, remotePeerId: String): Boolean =
            localPeerId.isNotBlank() && remotePeerId.isNotBlank() && localPeerId < remotePeerId

        const val EVENT_SERVICE_HASH_BYTES = 12
    }
}
