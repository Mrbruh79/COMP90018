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
                    recipientId = MeshGroup.ID,
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
                recipientId = MeshGroup.ID,
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

    private fun connectChatPeer() {
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

        fun payloadsTo(endpointId: String): List<ByteArray> = sent.mapNotNull { (id, bytes) ->
            bytes.takeIf { id == endpointId }
        }

        fun succeedConnection(endpointId: String) {
            listener?.onConnectionInitiated(endpointId, "peer", "1234")
            listener?.onConnectionSucceeded(endpointId)
        }

        fun receive(endpointId: String, bytes: ByteArray) {
            listener?.onBytesReceived(endpointId, bytes)
        }

        override fun startAdvertising(endpointName: String, serviceId: String) = Unit
        override fun startDiscovery(serviceId: String) = Unit
        override fun requestConnection(localEndpointName: String, endpointId: String) = Unit
        override fun acceptConnection(endpointId: String) = Unit
        override fun rejectConnection(endpointId: String) = Unit
        override fun send(endpointId: String, bytes: ByteArray) {
            sent += endpointId to bytes
        }
        override fun send(endpointId: String, bytes: ByteArray, onSuccess: () -> Unit) {
            sent += endpointId to bytes
            onSuccess()
        }
        override fun disconnect(endpointId: String) = Unit
        override fun stopAdvertising() = Unit
        override fun stopDiscovery() = Unit
        override fun disconnectAll() = Unit
    }

    private class RecordingNearbyListener : NearbyChatController.Listener {
        val chatMessages = mutableListOf<IncomingNearbyMessage>()
        val eventChats = mutableListOf<EventChatMessage>()

        override fun onDeviceFound(device: NearbyDevice) = Unit
        override fun onDeviceLost(endpointId: String) = Unit
        override fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String) = Unit
        override fun onConnected(peer: ConnectedPeer) = Unit
        override fun onMeshPeerFound(peer: GroupMember) = Unit
        override fun onGroupReceived(group: PrivateGroup) = Unit
        override fun onMessageReceived(message: IncomingNearbyMessage) {
            chatMessages += message
        }
        override fun onMessageSent(peerId: String, messageId: String) = Unit
        override fun onMessageDelivered(peerId: String, messageId: String) = Unit
        override fun onDisconnected(peerId: String) = Unit
        override fun onError(message: String) = Unit
        override fun onEventChatMessageReceived(message: EventChatMessage) {
            eventChats += message
        }
        override fun onEventAnnouncementReceived(announcement: EventAnnouncement) = Unit
        override fun onEventMutationReceived(mutation: EventMutation) = Unit
    }
}
