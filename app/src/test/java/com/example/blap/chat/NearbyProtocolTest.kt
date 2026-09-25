package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyProtocolTest {
    @Test
    fun messageRoundTripKeepsEveryField() {
        val packet = NearbyPacket.Message(
            messageId = "message-1",
            senderId = "alice",
            senderName = "Alice",
            senderPhoneHash = "alice-phone-hash",
            recipientId = "bob",
            sentAt = 1234L,
            text = "hello",
            hopsRemaining = 16,
            isGroup = false,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }

    @Test
    fun invalidPacketIsIgnored() {
        assertNull(NearbyProtocol.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun meshAcknowledgementRoundTripKeepsRoutingFields() {
        val packet = NearbyPacket.Acknowledgement(
            messageId = "message-2",
            senderId = "bob",
            recipientId = "alice",
            conversationId = MeshGroup.ID,
            hopsRemaining = 12,
            isGroup = true,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }

    @Test
    fun privateGroupDefinitionRoundTripKeepsMembers() {
        val packet = NearbyPacket.GroupDefinition(
            groupId = "group-1",
            name = "Trip crew",
            ownerId = "alice",
            createdAt = 500L,
            members = listOf(GroupMember("alice", "Alice"), GroupMember("bob", "Bob")),
            hopsRemaining = 16,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }

    @Test
    fun eventChatMessageRoundTripKeepsEventBoundary() {
        val packet = NearbyPacket.EventChatMessage(
            messageId = "event-message-1",
            eventId = "event-1",
            senderId = "alice-id",
            senderName = "Alice",
            text = "Meet at the main stage",
            sentAt = 1234L,
            hopsRemaining = 8,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }

    @Test
    fun eventAnnouncementRoundTripKeepsSignatureAndRevision() {
        val packet = NearbyPacket.EventAnnouncement(
            announcementId = "announcement-1",
            eventId = "event-1",
            adminId = "admin-1",
            adminName = "Organiser",
            text = "The keynote begins in ten minutes",
            createdAt = 1234L,
            revision = 1235L,
            signature = "signature",
            hopsRemaining = 16,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }

    @Test
    fun eventMutationRoundTripKeepsEditAndDeletionFields() {
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
            adminIds = listOf("admin-1", "admin-2"),
            adminPublicKeys = mapOf("admin-1" to "public-key"),
            createdAt = 100L,
            updatedAt = 200L,
            deletedAt = 200L,
            signature = "signature",
            hopsRemaining = 16,
        )

        assertEquals(packet, NearbyProtocol.decode(NearbyProtocol.encode(packet)))
    }
}
