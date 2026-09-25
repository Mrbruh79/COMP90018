package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EventCheckInCodecTest {
    @Test
    fun signedCheckInRoundTrips() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val issuedAt = 5_000_000L
        val payload = EventCheckInCodec.create(
            eventId = "event-1",
            adminId = "admin-1",
            privateKey = keys.private,
            issuedAt = issuedAt,
            lifetimeMillis = 5 * 60 * 1_000L,
            nonce = "fixed-nonce",
        )

        val credential = EventCheckInCodec.verify(
            payload,
            expectedEventId = "event-1",
            publicKey = keys.public,
            now = issuedAt + 1_000,
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
            issuedAt = 5_000_000L,
        )

        assertNull(EventCheckInCodec.verify(payload, "event-2", keys.public, 5_001_000L))
        assertNull(EventCheckInCodec.verify(payload + "x", "event-1", keys.public, 5_001_000L))
    }

    @Test
    fun expiredCheckInIsRejected() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val payload = EventCheckInCodec.create(
            eventId = "event-1",
            adminId = "admin-1",
            privateKey = keys.private,
            issuedAt = 5_000_000L,
            lifetimeMillis = 60_000L,
        )

        assertNull(EventCheckInCodec.verify(payload, "event-1", keys.public, 5_500_000L))
    }
}

