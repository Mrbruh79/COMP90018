package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EventCheckInCodecTest {
    @Test
    fun signedCheckInRoundTrips() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val payload = EventCheckInCodec.create(
            eventId = "event-1",
            adminId = "admin-1",
            privateKey = keys.private,
            nonce = "fixed-nonce",
        )

        val credential = EventCheckInCodec.verify(
            payload,
            expectedEventId = "event-1",
            publicKey = keys.public,
        )

        assertNotNull(credential)
        assertEquals("admin-1", credential?.adminId)
        assertEquals("fixed-nonce", credential?.nonce)
    }

    @Test
    fun wrongEventAndTamperingAreRejected() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val payload = EventCheckInCodec.create(
            eventId = "event-1",
            adminId = "admin-1",
            privateKey = keys.private,
        )

        assertNull(EventCheckInCodec.verify(payload, "event-2", keys.public))
        assertNull(EventCheckInCodec.verify(payload + "x", "event-1", keys.public))
    }

    @Test
    fun staticCheckInDoesNotExpireInsideTheCredential() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val payload = EventCheckInCodec.create(
            eventId = "event-1",
            adminId = "admin-1",
            privateKey = keys.private,
            nonce = "static-event-nonce",
        )

        assertNotNull(EventCheckInCodec.verify(payload, "event-1", keys.public))
    }
}
