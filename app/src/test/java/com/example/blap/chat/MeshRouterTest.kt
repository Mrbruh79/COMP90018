package com.example.blap.chat

import com.example.blap.event.EventVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class MeshRouterTest {
    private val router = MeshRouter()
    private val bob = ConnectedPeer("bob", "endpoint-bob", "Bob", "bob-hash")

    @Test
    fun protocolEnvelopeKeepsMagicAndVersion8() {
        val bytes = NearbyProtocol.encode(NearbyPacket.Hello("alice", "Alice", "hash"))
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(0x424C4150, input.readInt())
            assertEquals(8, input.readInt())
        }
    }

    @Test
    fun groupMessageDecrementsHopsWhenFirstSeen() {
        val decision = router.ingest(groupMessage(hopsRemaining = 3), chatContext())

        val delivered = decision.deliveries.filterIsInstance<MeshLocalDelivery.ChatMessage>().single()
        assertEquals("hello", delivered.message.text)
        assertEquals("group-1", delivered.message.conversationId)
        assertEquals(setOf("endpoint-carol"), decision.forwardTo)
        assertEquals(2, (decision.forwardPacket as NearbyPacket.Message).hopsRemaining)
    }

    @Test
    fun duplicateGroupMessageIsDeliveredButNotForwarded() {
        val packet = groupMessage()
        router.ingest(packet, chatContext())

        val second = router.ingest(packet, chatContext())

        assertEquals(1, second.deliveries.size)
        assertNull(second.forwardPacket)
        assertTrue(second.forwardTo.isEmpty())
    }

    @Test
    fun exhaustedHopBudgetIsDeliveredLocallyAndNotForwarded() {
        val decision = router.ingest(groupMessage(hopsRemaining = 0), chatContext())

        assertEquals(1, decision.deliveries.size)
        assertNull(decision.forwardPacket)
    }

    @Test
    fun hopCountsOutsideTheAllowedRangeAreDropped() {
        assertTrue(router.ingest(groupMessage(hopsRemaining = -1), chatContext()).isDrop)
        assertTrue(router.ingest(groupMessage(hopsRemaining = MeshRouter.MAX_HOPS + 1), chatContext()).isDrop)
    }

    @Test
    fun outgoingGroupMessageIsNotForwardedWhenItLoopsBack() {
        val packet = groupMessage()
        router.noteOutgoingMessage(packet.messageId)

        val decision = router.ingest(packet, chatContext())

        assertEquals(1, decision.deliveries.size)
        assertNull(decision.forwardPacket)
    }

    @Test
    fun directMessagesAreNotRelayed() {
        val packet = NearbyPacket.Message(
            messageId = "direct-1",
            senderId = "bob",
            senderName = "Bob",
            senderPhoneHash = "bob-hash",
            recipientId = "alice",
            sentAt = 10L,
            text = "private",
            hopsRemaining = 16,
            isGroup = false,
        )

        val decision = router.ingest(packet, chatContext())

        val delivered = decision.deliveries.filterIsInstance<MeshLocalDelivery.ChatMessage>().single()
        assertEquals("bob", delivered.message.conversationId)
        assertEquals("Bob", delivered.message.senderName)
        assertNull(decision.forwardPacket)
    }

    @Test
    fun chatPacketsOnEventEndpointsAreDropped() {
        assertTrue(router.ingest(groupMessage(), eventContext()).isDrop)
    }

    @Test
    fun eventPacketsOnChatEndpointsAreDropped() {
        assertTrue(router.ingest(eventChat(), chatContext()).isDrop)
    }

    @Test
    fun eventChatIsIsolatedToTheActiveEventAndGrantedPeers() {
        assertTrue(router.ingest(eventChat(), eventContext(accessGranted = false)).isDrop)
        assertTrue(router.ingest(eventChat(eventId = "other-event"), eventContext()).isDrop)

        val first = router.ingest(eventChat(hopsRemaining = 4), eventContext())
        val delivered = first.deliveries.filterIsInstance<MeshLocalDelivery.EventChat>().single()
        assertEquals("Meet at the gate", delivered.message.text)
        assertEquals(setOf("endpoint-dana"), first.forwardTo)
        assertEquals(3, (first.forwardPacket as NearbyPacket.EventChatMessage).hopsRemaining)

        val duplicate = router.ingest(eventChat(), eventContext())
        assertTrue(duplicate.isDrop)
    }

    @Test
    fun eventPresenceDoesNotLeakAcrossEventsAndIsNotForwarded() {
        val otherEvent = NearbyPacket.EventPresence(
            eventId = "other-event",
            peerId = "bob",
            name = "Bob",
            userId = "bob-uid",
            active = true,
            accessGranted = true,
        )
        assertTrue(router.ingest(otherEvent, eventContext()).isDrop)

        val decision = router.ingest(
            NearbyPacket.EventPresence(
                eventId = "event-1",
                peerId = "bob",
                name = "Bob",
                userId = "bob-uid",
                active = true,
                accessGranted = true,
            ),
            eventContext(),
        )
        val peer = decision.deliveries.filterIsInstance<MeshLocalDelivery.EventPeer>().single()
        assertEquals("bob-uid", peer.userId)
        assertNull(decision.forwardPacket)
    }

    @Test
    fun eventMutationRoundTripKeepsVisibilityAndDeletion() {
        val packet = NearbyPacket.EventMutation(
            eventId = "event-1",
            adminId = "admin-1",
            title = "Updated event",
            description = "Updated description",
            venueName = "New venue",
            latitude = -37.8136,
            longitude = 144.9631,
            radiusMetres = 120.0,
            startsAt = 1_000L,
            endsAt = 2_000L,
            createdBy = "admin-1",
            adminIds = listOf("admin-1"),
            adminPublicKeys = mapOf("admin-1" to "public-key"),
            visibility = EventVisibility.PUBLIC.name,
            requiresSignIn = false,
            venueCheckInPayload = "",
            createdAt = 100L,
            updatedAt = 200L,
            deletedAt = 200L,
            signature = "signature",
            hopsRemaining = 8,
        )

        val decision = router.ingest(packet, eventContext())
        val mutation = decision.deliveries.filterIsInstance<MeshLocalDelivery.IncomingEventMutation>().single().mutation
        assertEquals(EventVisibility.PUBLIC, mutation.event.visibility)
        assertEquals(200L, mutation.event.deletedAt)
        assertEquals(7, (decision.forwardPacket as NearbyPacket.EventMutation).hopsRemaining)
    }

    @Test
    fun groupAcknowledgementsDeduplicateAndHonorHopLimits() {
        val packet = NearbyPacket.Acknowledgement(
            messageId = "message-1",
            senderId = "carol",
            recipientId = "alice",
            conversationId = "group-1",
            hopsRemaining = 2,
            isGroup = true,
        )

        val first = router.ingest(packet, chatContext())
        assertEquals(
            MeshLocalDelivery.Acknowledged("group-1", "message-1"),
            first.deliveries.single(),
        )
        assertEquals(1, (first.forwardPacket as NearbyPacket.Acknowledgement).hopsRemaining)

        assertTrue(router.ingest(packet, chatContext()).isDrop)
    }

    private fun groupMessage(
        messageId: String = "message-1",
        hopsRemaining: Int = 16,
    ) = NearbyPacket.Message(
        messageId = messageId,
        senderId = "bob",
        senderName = "Bob",
        senderPhoneHash = "bob-hash",
        recipientId = "group-1",
        sentAt = 10L,
        text = "hello",
        hopsRemaining = hopsRemaining,
        isGroup = true,
    )

    private fun eventChat(
        eventId: String = "event-1",
        hopsRemaining: Int = 8,
    ) = NearbyPacket.EventChatMessage(
        messageId = "event-message-1",
        eventId = eventId,
        senderId = "bob",
        senderName = "Bob",
        text = "Meet at the gate",
        sentAt = 20L,
        hopsRemaining = hopsRemaining,
    )

    private fun chatContext() = MeshContext(
        localPeerId = "alice",
        fromEndpointId = "endpoint-bob",
        fromPeer = bob,
        isEventEndpoint = false,
        activeEventId = null,
        accessGranted = false,
        chatForwardTargets = setOf("endpoint-carol"),
        eventForwardTargets = emptySet(),
    )

    private fun eventContext(accessGranted: Boolean = true) = MeshContext(
        localPeerId = "alice",
        fromEndpointId = "endpoint-bob",
        fromPeer = bob,
        isEventEndpoint = true,
        activeEventId = "event-1",
        accessGranted = accessGranted,
        chatForwardTargets = emptySet(),
        eventForwardTargets = setOf("endpoint-dana"),
    )
}
