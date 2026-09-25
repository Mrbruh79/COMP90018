package com.example.blap.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyEventDiscoveryTest {
    @Test
    fun eachEventUsesAnIsolatedNearbyService() {
        val first = NearbyChatManager.eventServiceId("event-1")
        val same = NearbyChatManager.eventServiceId("event-1")
        val second = NearbyChatManager.eventServiceId("event-2")

        assertTrue(first.startsWith("com.example.blap.event."))
        assertTrue(first == same)
        assertNotEquals(first, second)
        assertFalse(first.contains("event-1"))
    }

    @Test
    fun eventEndpointNamesExposeOnlyTheStablePeerId() {
        val endpointName = "${NearbyChatManager.EVENT_ENDPOINT_PREFIX}peer-123"

        val peerId = with(NearbyChatManager) { endpointName.eventPeerIdOrNull() }

        assertTrue(peerId == "peer-123")
        assertNull(with(NearbyChatManager) { "Alice".eventPeerIdOrNull() })
    }

    @Test
    fun exactlyOnePhoneInitiatesEachEventConnection() {
        assertTrue(NearbyChatManager.shouldInitiateEventConnection("alice", "bob"))
        assertFalse(NearbyChatManager.shouldInitiateEventConnection("bob", "alice"))
        assertFalse(NearbyChatManager.shouldInitiateEventConnection("alice", "alice"))
    }
}
