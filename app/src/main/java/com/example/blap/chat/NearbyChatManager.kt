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
import java.nio.charset.StandardCharsets

class NearbyChatManager(context: Context) : NearbyChatController {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val knownDevices = mutableMapOf<String, NearbyDevice>()

    private var localDisplayName = ""
    private var pendingEndpointId: String? = null
    private var pendingDevice: NearbyDevice? = null
    private var connectedEndpointId: String? = null

    override var listener: NearbyChatController.Listener? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId != connectedEndpointId || payload.type != Payload.Type.BYTES) return

            payload.asBytes()?.let { bytes ->
                listener?.onMessageReceived(String(bytes, StandardCharsets.UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate,
        ) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val device = NearbyDevice(endpointId, info.endpointName)
            val otherPendingEndpoint = pendingEndpointId?.takeIf { it != endpointId }
            if (connectedEndpointId != null || otherPendingEndpoint != null) {
                connectionsClient.rejectConnection(endpointId)
                return
            }

            pendingEndpointId = endpointId
            pendingDevice = device
            // Discovery competes for the same radios used to negotiate the direct link.
            stopScanning()
            listener?.onConnectionInitiated(device, info.authenticationDigits)
            acceptConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != pendingEndpointId) return

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpointId = endpointId
                    val device = pendingDevice ?: knownDevices[endpointId]
                        ?: NearbyDevice(endpointId, "Nearby device")
                    pendingEndpointId = null
                    pendingDevice = null
                    stopScanning()
                    listener?.onConnected(device)
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    clearPendingConnection()
                    listener?.onError("The other device declined the connection.")
                }

                else -> {
                    clearPendingConnection()
                    listener?.onError(connectionFailureMessage(result.status.statusCode))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != connectedEndpointId) return
            connectedEndpointId = null
            listener?.onDisconnected()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val device = NearbyDevice(endpointId, info.endpointName)
            knownDevices[endpointId] = device
            listener?.onDeviceFound(device)
        }

        override fun onEndpointLost(endpointId: String) {
            knownDevices.remove(endpointId)
            listener?.onDeviceLost(endpointId)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(displayName: String) {
        localDisplayName = displayName
        knownDevices.clear()
        clearPendingConnection()
        connectionsClient.stopAdvertising()

        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient
                .startAdvertising(
                    displayName,
                    SERVICE_ID,
                    connectionLifecycleCallback,
                    options,
                )
                .addOnFailureListener { exception ->
                    listener?.onError("Advertising failed: ${exception.readableMessage()}")
                }
        } catch (exception: SecurityException) {
            listener?.onError("Nearby permissions are required to advertise this phone.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnFailureListener { exception ->
                    listener?.onError("Discovery failed: ${exception.readableMessage()}")
                }
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to find other phones.")
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
        knownMeshPeers.clear()
        cachedGroupDefinitions.clear()
        cachedGroupMessages.clear()
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
        knownMeshPeers[peer.peerId] = GroupMember(peer.peerId, peer.name, peer.phoneHash)
        knownDevices.remove(endpointId)
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

    private fun handleMessage(endpointId: String, packet: NearbyPacket.Message) {
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
        val peer = peerByEndpoint[endpointId] ?: return
        if (packet.isGroup) {
            if (packet.hopsRemaining !in 0..MAX_HOPS) return
            if (!rememberId(seenAcknowledgements, acknowledgementId(packet))) return
            if (packet.recipientId == localPeerId) {
                listener?.onMessageDelivered(MeshGroup.ID, packet.messageId)
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

    private companion object {
        const val SERVICE_ID = "com.example.blap"
        const val MAX_HOPS = 16
        const val MAX_REMEMBERED_IDS = 10_000
        const val MAX_GROUP_MEMBERS = 100
        const val MAX_CACHED_GROUP_MESSAGES = 2_000
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER

        fun boundedIdSet() = LinkedHashSet<String>()
    }
}
