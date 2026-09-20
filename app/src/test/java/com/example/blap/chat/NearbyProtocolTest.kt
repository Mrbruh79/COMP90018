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
    fun handshakePacketsRoundTripAndSealedFramesStayOpaque() {
        val nonce = NearbyPacket.HandshakeNonce("alice", "ab".repeat(32))
        val auth = NearbyPacket.HandshakeAuth("cd".repeat(32))

        assertEquals(nonce, NearbyProtocol.decode(NearbyProtocol.encode(nonce)))
        assertEquals(auth, NearbyProtocol.decode(NearbyProtocol.encode(auth)))
        assertNull(NearbyProtocol.decode(NearbyProtocol.encodeSealed(ByteArray(32) { 7 })))
    }
}
