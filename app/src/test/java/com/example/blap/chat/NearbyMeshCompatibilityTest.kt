package com.example.blap.chat

import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMeshCompatibilityTest {
    private val transport = FakeNearbyConnectionTransport()
    private val manager = NearbyChatManager(transport)
    private val listener = RecordingNearbyListener()

    @Test fun failedLastConnectionRestartsDiscoveryWithoutTogglingNearby() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        transport.findEndpoint("old-endpoint", "Bob")
        manager.connectToDevice("old-endpoint")
        val scans = transport.discoveredServiceIds.size
        val resets = transport.disconnectAllCalls
        transport.listener?.onConnectionFailed("old-endpoint", "Link failed")
        assertEquals(scans + 1, transport.discoveredServiceIds.size)
        assertEquals(resets + 1, transport.disconnectAllCalls)
        transport.findEndpoint("new-endpoint", "Bob")
        manager.connectToDevice("new-endpoint")
        assertEquals(listOf("old-endpoint", "new-endpoint"), transport.requestedEndpointIds)
    }

    @Test fun lateConnectionFailureRemovesOnlyFailedPeerAndRestartsWhenLastLinkDrops() {
        manager.listener = listener
        connectChatPeer()
        val scans = transport.discoveredServiceIds.size
        transport.listener?.onConnectionFailed("endpoint-bob", "Link failed")
        assertEquals(listOf("bob"), listener.disconnected)
        assertEquals(scans, transport.discoveredServiceIds.size)
        transport.listener?.onConnectionFailed("endpoint-carol", "Link failed")
        assertEquals(listOf("bob", "carol"), listener.disconnected)
        assertEquals(scans + 1, transport.discoveredServiceIds.size)
        transport.sent.clear()
        manager.sendMessage(OutgoingNearbyMessage("not-sent", "bob", "No stale link", 1L))
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun refreshingAConnectedEventDoesNotRestartTheRadioScan() {
        connectEventPeer()
        val scans = transport.discoveredServiceIds.size
        val resets = transport.disconnectAllCalls
        manager.refreshEventMesh("event-1")
        assertEquals(scans, transport.discoveredServiceIds.size)
        assertEquals(resets, transport.disconnectAllCalls)
    }

    @Test
    fun privateConnectionsWaitForApprovalAndCannotReceiveBeforeIt() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        transport.listener?.onConnectionInitiated("bob-endpoint", "Bob", "1234")
        assertTrue(transport.accepted.isEmpty())
        assertEquals("1234", listener.requests.single().authenticationDigits)
        transport.receive("bob-endpoint", NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "")))
        assertTrue(listener.connected.isEmpty())
        manager.acceptConnection("bob-endpoint")
        assertEquals(listOf("bob-endpoint"), transport.accepted)
        transport.listener?.onConnectionSucceeded("bob-endpoint")
        transport.receive("bob-endpoint", NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "")))
        assertEquals("bob", listener.connected.single().peerId)
    }

    @Test
    fun decliningAPrivateConnectionLeavesNoApprovedEndpoint() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        transport.listener?.onConnectionInitiated("bob-endpoint", "Bob", "1234")
        manager.rejectConnection("bob-endpoint")
        manager.acceptConnection("bob-endpoint")
        transport.listener?.onConnectionSucceeded("bob-endpoint")
        assertTrue(transport.accepted.isEmpty())
        assertEquals(listOf("bob-endpoint"), transport.rejected)
        assertTrue(listener.connected.isEmpty())
        assertEquals(listOf("bob-endpoint"), listener.closed)
    }

    @Test
    fun losingTheAdvertisementDoesNotCancelAHandshakeAwaitingApproval() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        transport.listener?.onConnectionInitiated("bob-endpoint", "Bob", "1234")
        transport.loseEndpoint("bob-endpoint")
        manager.acceptConnection("bob-endpoint")
        assertEquals(listOf("bob-endpoint"), transport.accepted)
        assertTrue(transport.rejected.isEmpty())
    }

    @Test
    fun bothPhonesCanRediscoverAndRequestAfterDisconnecting() {
        val otherTransport = FakeNearbyConnectionTransport()
        val otherManager = NearbyChatManager(otherTransport)
        val otherListener = RecordingNearbyListener()
        listOf(Triple(manager, transport, listener), Triple(otherManager, otherTransport, otherListener)).forEach { (m, t, l) ->
            m.listener = l
            m.startAdvertising("Me", "local", "")
            m.startDiscovery()
            t.findEndpoint("remote-endpoint", "Remote")
            m.connectToDevice("remote-endpoint")
            t.listener?.onConnectionInitiated("remote-endpoint", "Remote", "1234")
            m.acceptConnection("remote-endpoint")
            t.listener?.onConnectionSucceeded("remote-endpoint")
            t.receive("remote-endpoint", NearbyProtocol.encode(NearbyPacket.Hello("remote", "Remote", "")))
            l.devices.clear()
            val scans = t.discoveredServiceIds.size
            t.disconnectEndpoint("remote-endpoint")
            assertEquals("remote-endpoint", l.devices.single().endpointId)
            assertEquals(scans + 1, t.discoveredServiceIds.size)
            t.findEndpoint("remote-endpoint", "Remote")
            m.connectToDevice("remote-endpoint")
            assertEquals(listOf("remote-endpoint", "remote-endpoint"), t.requestedEndpointIds)
        }
    }

    @Test
    fun localDisconnectOffersReconnectWithoutWaitingForAnSdkCallback() {
        manager.listener = listener
        connectChatPeer()
        listener.devices.clear()
        manager.disconnect("bob")
        assertEquals("endpoint-bob", listener.devices.single().endpointId)
        transport.findEndpoint("endpoint-bob", "Bob")
        manager.connectToDevice("endpoint-bob")
        assertEquals(listOf("endpoint-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun switchingNearbyOffClearsApprovalAndRestartUsesFreshEndpoints() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        transport.findEndpoint("old-endpoint", "Bob")
        transport.listener?.onConnectionInitiated("old-endpoint", "Bob", "1234")
        manager.stop()
        transport.findEndpoint("late-endpoint", "Stale")
        assertEquals(listOf("old-endpoint"), listener.devices.map { it.endpointId })
        manager.acceptConnection("old-endpoint")
        assertTrue(transport.accepted.isEmpty())
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        manager.connectToDevice("old-endpoint")
        assertTrue(transport.requestedEndpointIds.isEmpty())
        transport.findEndpoint("new-endpoint", "Bob")
        manager.connectToDevice("new-endpoint")
        assertEquals(listOf("new-endpoint"), transport.requestedEndpointIds)
    }

    @Test
    fun legacyPublicRoomTrafficIsNeitherSentNorRelayed() {
        manager.listener = listener
        connectChatPeer()
        manager.sendMessage(OutgoingNearbyMessage("old-out", MeshGroup.ID, "public", 1L, true))
        transport.receive("endpoint-bob", NearbyProtocol.encode(NearbyPacket.Message("old-in", "bob", "Bob", "",
            MeshGroup.ID, 1L, "public", 3, true)))
        assertTrue(transport.sent.isEmpty())
        assertTrue(listener.chatMessages.isEmpty())
    }

    @Test
    fun groupRelayPreservesPacketFormatAndDecrementsHops() {
        manager.listener = listener
        manager.eventListener = listener
        connectChatPeer()

        transport.receive(
            "endpoint-bob",
            NearbyProtocol.encode(
                NearbyPacket.Message(
                    messageId = "message-1",
                    senderId = "bob",
                    senderName = "Bob",
                    senderPhoneHash = "bob-hash",
                    recipientId = "private-group",
                    sentAt = 10L,
                    text = "hello mesh",
                    hopsRemaining = 3,
                    isGroup = true,
                ),
            ),
        )

        val forwarded = NearbyProtocol.decode(transport.payloadsTo("endpoint-carol").last()) as NearbyPacket.Message
        assertEquals("hello mesh", forwarded.text)
        assertEquals(2, forwarded.hopsRemaining)
        assertEquals("message-1", listener.chatMessages.single().messageId)
    }

    @Test
    fun duplicateMeshMessagesAreNotForwardedAgain() {
        manager.listener = listener
        manager.eventListener = listener
        connectChatPeer()
        val packet = NearbyProtocol.encode(
            NearbyPacket.Message(
                messageId = "message-1",
                senderId = "bob",
                senderName = "Bob",
                senderPhoneHash = "bob-hash",
                recipientId = "private-group",
                sentAt = 10L,
                text = "hello mesh",
                hopsRemaining = 3,
                isGroup = true,
            ),
        )
        transport.receive("endpoint-bob", packet)
        val forwardedAfterFirst = transport.payloadsTo("endpoint-carol").size

        transport.receive("endpoint-bob", packet)

        assertEquals(forwardedAfterFirst, transport.payloadsTo("endpoint-carol").size)
        assertEquals(2, listener.chatMessages.size)
    }

    @Test
    fun eventMeshDoesNotForwardOpenChatPackets() {
        manager.listener = listener
        manager.eventListener = listener
        connectEventPeer()

        transport.receive(
            "endpoint-bob",
            NearbyProtocol.encode(
                NearbyPacket.Message(
                    messageId = "message-1",
                    senderId = "bob",
                    senderName = "Bob",
                    senderPhoneHash = "bob-hash",
                    recipientId = MeshGroup.ID,
                    sentAt = 10L,
                    text = "should not leak",
                    hopsRemaining = 16,
                    isGroup = true,
                ),
            ),
        )

        assertTrue(listener.chatMessages.isEmpty())
        assertTrue(transport.payloadsTo("endpoint-dana").isEmpty())
    }

    @Test
    fun eventChatUsesItsOwnListenerAndStaysInsideTheActiveEventMesh() {
        val eventListener = RecordingNearbyListener()
        manager.listener = listener
        manager.eventListener = eventListener
        connectEventPeer()

        transport.receive(
            "endpoint-bob",
            NearbyProtocol.encode(
                NearbyPacket.EventChatMessage(
                    messageId = "event-message-1",
                    eventId = "event-1",
                    senderId = "bob",
                    senderName = "Bob",
                    text = "Meet at the gate",
                    sentAt = 20L,
                    hopsRemaining = 4,
                ),
            ),
        )

        val forwarded = NearbyProtocol.decode(
            transport.payloadsTo("endpoint-dana").last(),
        ) as NearbyPacket.EventChatMessage
        assertEquals(3, forwarded.hopsRemaining)
        assertEquals("Meet at the gate", eventListener.eventChats.single().text)
        assertTrue(listener.eventChats.isEmpty())
        assertTrue(transport.payloadsTo("endpoint-carol").isEmpty())
    }

    @Test
    fun restartingNearbyResumesTheSamePrivateEventMeshAndCanSendAgain() {
        manager.listener = listener
        manager.eventListener = listener
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "private-secret", "alice-uid", accessGranted = true)
        val privateServiceId = EventMeshSession.eventServiceId("event-1", "private-secret")

        manager.stop()
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()

        assertEquals(privateServiceId, transport.advertisedServiceIds.last())
        assertEquals(privateServiceId, transport.discoveredServiceIds.last())

        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")
        assertEquals(listOf("endpoint-bob"), transport.requestedEndpointIds)
        transport.succeedConnection("endpoint-bob")
        transport.receive(
            "endpoint-bob",
            NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "bob-hash")),
        )
        transport.sent.clear()

        manager.sendEventChatMessage(
            EventChatMessage(
                id = "event-message-after-restart",
                eventId = "event-1",
                senderId = "alice",
                senderName = "Alice",
                text = "Mesh is back",
                createdAt = 30L,
            ),
        )

        val sent = NearbyProtocol.decode(transport.payloadsTo("endpoint-bob").single())
            as NearbyPacket.EventChatMessage
        assertEquals("event-message-after-restart", sent.messageId)
        assertEquals("Mesh is back", sent.text)
    }

    @Test
    fun restartingNearbyAutomaticallyReconnectsToTheSamePublicEventMesh() {
        manager.listener = listener
        manager.eventListener = listener
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "", "alice-uid", accessGranted = true)
        val publicServiceId = EventMeshSession.eventServiceId("event-1")

        manager.stop()
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")

        assertEquals(publicServiceId, transport.advertisedServiceIds.last())
        assertEquals(publicServiceId, transport.discoveredServiceIds.last())
        assertEquals(listOf("endpoint-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun reenteringAnEmptyEventMeshRestartsAdvertisingAndDiscovery() {
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "private-secret", "alice-uid", accessGranted = true)
        val advertisingStarts = transport.advertisedServiceIds.size
        val discoveryStarts = transport.discoveredServiceIds.size

        manager.setActiveEvent("event-1", "private-secret", "alice-uid", accessGranted = true)

        assertEquals(advertisingStarts + 1, transport.advertisedServiceIds.size)
        assertEquals(discoveryStarts + 1, transport.discoveredServiceIds.size)
    }

    @Test
    fun rediscoveryBeforeOldDisconnectIsReplayedAfterTheLinkCloses() {
        manager.listener = listener
        manager.eventListener = listener
        connectEventPeer()

        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")
        assertTrue(transport.requestedEndpointIds.isEmpty())
        transport.loseEndpoint("endpoint-bob")
        transport.disconnectEndpoint("endpoint-bob")

        assertEquals(listOf("endpoint-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun lostPendingEventEndpointCanBeRequestedWhenItAppearsAgain() {
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "", "alice-uid", accessGranted = true)

        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")
        transport.loseEndpoint("endpoint-bob")
        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")

        assertEquals(listOf("endpoint-bob", "endpoint-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun announcementRefreshDiscoversEventPeersWithoutOpeningOnSiteChat() {
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "private-secret", "alice-uid", accessGranted = true)
        val scans = transport.discoveredServiceIds.size

        manager.refreshEventMesh("event-1")
        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")

        assertEquals(scans + 1, transport.discoveredServiceIds.size)
        assertEquals(EventMeshSession.eventServiceId("event-1", "private-secret"), transport.discoveredServiceIds.last())
        assertEquals(listOf("endpoint-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun announcementRefreshAsksConnectedEventPeersForHistoryWithoutDisconnecting() {
        connectEventPeer()
        val disconnects = transport.disconnectAllCalls
        val advertisements = transport.advertisedServiceIds.size

        manager.refreshEventMesh("event-1")

        assertEquals(disconnects, transport.disconnectAllCalls)
        assertEquals(advertisements, transport.advertisedServiceIds.size)
        listOf("endpoint-bob", "endpoint-dana").forEach { endpoint ->
            val presence = NearbyProtocol.decode(transport.payloadsTo(endpoint).single()) as NearbyPacket.EventPresence
            assertEquals("event-1", presence.eventId)
            assertTrue(presence.accessGranted)
        }
    }

    @Test
    fun announcementRefreshDoesNotInterruptAnEventHandshake() {
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "secret", "alice-uid", accessGranted = true)
        transport.findEndpoint("endpoint-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")
        val scans = transport.discoveredServiceIds.size
        manager.refreshEventMesh("event-1")
        assertEquals(scans, transport.discoveredServiceIds.size)
        transport.succeedConnection("endpoint-bob")
        assertTrue(transport.payloadsTo("endpoint-bob").isNotEmpty())
    }

    @Test
    fun announcementRefreshCannotEnableNearbyOrJoinAnotherEvent() {
        connectEventPeer()
        val scans = transport.discoveredServiceIds.size
        manager.refreshEventMesh("other-event")
        assertEquals(scans, transport.discoveredServiceIds.size)
        assertTrue(transport.sent.isEmpty())
        manager.stop()
        manager.refreshEventMesh("event-1")
        assertEquals(scans, transport.discoveredServiceIds.size)
    }

    @Test
    fun lastEventLinkDroppingRestartsDiscoveryForAnnouncementSync() {
        connectEventPeer()
        val scans = transport.discoveredServiceIds.size
        transport.disconnectEndpoint("endpoint-bob")
        assertEquals(scans, transport.discoveredServiceIds.size)
        transport.disconnectEndpoint("endpoint-dana")
        assertEquals(scans + 1, transport.discoveredServiceIds.size)
        transport.findEndpoint("new-bob", "${EventMeshSession.EVENT_ENDPOINT_PREFIX}bob")
        assertEquals(listOf("new-bob"), transport.requestedEndpointIds)
    }

    @Test
    fun storedAnnouncementsSyncOnlyToPeersInTheActiveEvent() {
        connectEventPeer()
        val announcement = EventAnnouncement(id = "announcement-1", eventId = "event-1", adminId = "alice-uid",
            adminName = "Alice", text = "Gate changed", createdAt = 20L, signature = "signed")
        manager.synchronizeEventAnnouncements("bob", listOf(announcement, announcement.copy(id = "other", eventId = "event-2")))
        val packet = NearbyProtocol.decode(transport.payloadsTo("endpoint-bob").single()) as NearbyPacket.EventAnnouncement
        assertEquals("announcement-1", packet.announcementId)
        assertEquals("signed", packet.signature)
        assertTrue(transport.payloadsTo("endpoint-dana").isEmpty())
    }

    @Test
    fun savedDevicesMustProveTheirKeyBeforePrivateTrafficIsDelivered() {
        manager.listener = listener
        manager.configureAccount("alice", "alice-uid")
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        val token = ByteArray(32) { it.toByte() }
        val keys = NearbyIdentityProof.generateKeys()
        val unsigned = NearbyPacket.Hello("bob", "Bob", "", "bob", "bob-uid", NearbyIdentityProof.publicKey(keys))
        val hello = unsigned.copy(signature = NearbyIdentityProof.sign(unsigned, token, keys))
        transport.listener?.onConnectionInitiated("endpoint-bob", NearbyEndpointIdentity.name("bob", "bob", "Bob"), "1234", token)
        manager.acceptKnownConnection("endpoint-bob", TrustedNearbyIdentity("bob", "bob", "bob-uid", hello.publicKey, true))
        transport.listener?.onConnectionSucceeded("endpoint-bob")
        val message = NearbyPacket.Message("dm", "bob", "Bob", "", "alice", 1L, "Hello", 1, false)
        transport.receive("endpoint-bob", NearbyProtocol.encode(message))
        assertTrue(listener.chatMessages.isEmpty())
        transport.receive("endpoint-bob", NearbyProtocol.encode(hello))
        transport.receive("endpoint-bob", NearbyProtocol.encode(message))
        assertEquals("bob", listener.connected.single().username)
        assertTrue(listener.connected.single().accountVerified)
        assertEquals("Hello", listener.chatMessages.single().text)
    }

    @Test
    fun copiedUsernameWithAnotherKeyCannotImpersonateASavedContact() {
        manager.listener = listener
        manager.startAdvertising("Alice", "alice", "")
        manager.startDiscovery()
        val token = ByteArray(32) { it.toByte() }
        val realKeys = NearbyIdentityProof.generateKeys()
        val attackerKeys = NearbyIdentityProof.generateKeys()
        val unsigned = NearbyPacket.Hello("bob", "Bob", "", "bob", "bob-uid", NearbyIdentityProof.publicKey(attackerKeys))
        val hello = unsigned.copy(signature = NearbyIdentityProof.sign(unsigned, token, attackerKeys))
        transport.listener?.onConnectionInitiated("endpoint-bob", NearbyEndpointIdentity.name("bob", "bob", "Bob"), "1234", token)
        manager.acceptKnownConnection("endpoint-bob", TrustedNearbyIdentity("bob", "bob", "bob-uid", NearbyIdentityProof.publicKey(realKeys), true))
        transport.listener?.onConnectionSucceeded("endpoint-bob")
        transport.receive("endpoint-bob", NearbyProtocol.encode(hello))
        assertTrue(listener.connected.isEmpty())
        assertEquals(listOf("endpoint-bob"), transport.disconnectedEndpointIds)
    }

    private fun connectChatPeer() {
        transport.managerAccept = manager::acceptConnection
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        transport.succeedConnection("endpoint-bob")
        transport.succeedConnection("endpoint-carol")
        transport.receive("endpoint-bob", NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "bob-hash")))
        transport.receive("endpoint-carol", NearbyProtocol.encode(NearbyPacket.Hello("carol", "Carol", "carol-hash")))
        transport.sent.clear()
    }

    private fun connectEventPeer() {
        manager.startAdvertising("Alice", "alice", "alice-hash")
        manager.startDiscovery()
        manager.setActiveEvent("event-1", "secret-one", "alice-uid", accessGranted = true)
        transport.succeedConnection("endpoint-bob")
        transport.succeedConnection("endpoint-dana")
        transport.receive("endpoint-bob", NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "bob-hash")))
        transport.receive("endpoint-dana", NearbyProtocol.encode(NearbyPacket.Hello("dana", "Dana", "dana-hash")))
        transport.sent.clear()
    }

    private class FakeNearbyConnectionTransport : NearbyConnectionTransport {
        override var listener: NearbyConnectionTransport.Listener? = null
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val advertisedServiceIds = mutableListOf<String>()
        val discoveredServiceIds = mutableListOf<String>()
        val requestedEndpointIds = mutableListOf<String>()
        var disconnectAllCalls = 0
        val disconnectedEndpointIds = mutableListOf<String>()

        fun payloadsTo(endpointId: String): List<ByteArray> = sent.mapNotNull { (id, bytes) ->
            bytes.takeIf { id == endpointId }
        }

        fun succeedConnection(endpointId: String) {
            listener?.onConnectionInitiated(endpointId, "${EventMeshSession.EVENT_ENDPOINT_PREFIX}peer", "1234")
            if (endpointId !in accepted) managerAccept?.invoke(endpointId)
            listener?.onConnectionSucceeded(endpointId)
        }

        fun receive(endpointId: String, bytes: ByteArray) {
            listener?.onBytesReceived(endpointId, bytes)
        }

        fun findEndpoint(endpointId: String, endpointName: String) {
            listener?.onEndpointFound(endpointId, endpointName)
        }

        fun disconnectEndpoint(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }

        fun loseEndpoint(endpointId: String) {
            listener?.onEndpointLost(endpointId)
        }

        override fun startAdvertising(endpointName: String, serviceId: String) {
            advertisedServiceIds += serviceId
        }
        override fun startDiscovery(serviceId: String) {
            discoveredServiceIds += serviceId
        }
        override fun requestConnection(localEndpointName: String, endpointId: String) {
            requestedEndpointIds += endpointId
        }
        var managerAccept: ((String) -> Unit)? = null
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        override fun acceptConnection(endpointId: String) { accepted += endpointId }
        override fun rejectConnection(endpointId: String) { rejected += endpointId }
        override fun send(endpointId: String, bytes: ByteArray) {
            sent += endpointId to bytes
        }
        override fun send(endpointId: String, bytes: ByteArray, onSuccess: () -> Unit) {
            sent += endpointId to bytes
            onSuccess()
        }
        override fun disconnect(endpointId: String) { disconnectedEndpointIds += endpointId }
        override fun stopAdvertising() = Unit
        override fun stopDiscovery() = Unit
        override fun disconnectAll() { disconnectAllCalls++ }
    }

    private class RecordingNearbyListener : NearbyChatController.Listener {
        val devices = mutableListOf<NearbyDevice>()
        val requests = mutableListOf<NearbyConnectionRequest>()
        val connected = mutableListOf<ConnectedPeer>()
        val closed = mutableListOf<String>()
        val disconnected = mutableListOf<String>()
        val chatMessages = mutableListOf<IncomingNearbyMessage>()
        val eventChats = mutableListOf<EventChatMessage>()

        override fun onDeviceFound(device: NearbyDevice) { devices += device }
        override fun onDeviceLost(endpointId: String) = Unit
        override fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String) { requests += NearbyConnectionRequest(device, authenticationDigits) }
        override fun onConnectionClosed(endpointId: String) { closed += endpointId }
        override fun onConnected(peer: ConnectedPeer) { connected += peer }
        override fun onMeshPeerFound(peer: GroupMember) = Unit
        override fun onGroupReceived(group: PrivateGroup) = Unit
        override fun onMessageReceived(message: IncomingNearbyMessage) {
            chatMessages += message
        }
        override fun onMessageSent(peerId: String, messageId: String) = Unit
        override fun onMessageDelivered(peerId: String, messageId: String) = Unit
        override fun onDisconnected(peerId: String) { disconnected += peerId }
        override fun onError(message: String) = Unit
        override fun onEventChatMessageReceived(message: EventChatMessage) {
            eventChats += message
        }
        override fun onEventAnnouncementReceived(announcement: EventAnnouncement) = Unit
        override fun onEventMutationReceived(mutation: EventMutation) = Unit
    }
}
