package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMeshSessionTest {
    @Test
    fun eachEventUsesAnIsolatedNearbyService() {
        val first = EventMeshSession.eventServiceId("event-1")
        val same = EventMeshSession.eventServiceId("event-1")
        val second = EventMeshSession.eventServiceId("event-2")

        assertTrue(first.startsWith("com.example.blap.event."))
        assertEquals(first, same)
        assertNotEquals(first, second)
        assertFalse(first.contains("event-1"))
    }

    @Test
    fun privateEventServiceAlsoDependsOnItsSecret() {
        val first = EventMeshSession.eventServiceId("event-1", "secret-one")
        val same = EventMeshSession.eventServiceId("event-1", "secret-one")
        val differentSecret = EventMeshSession.eventServiceId("event-1", "secret-two")

        assertEquals(first, same)
        assertNotEquals(first, differentSecret)
        assertFalse(first.contains("secret-one"))
    }

    @Test
    fun eventEndpointNamesExposeOnlyTheStablePeerId() {
        val endpointName = "${EventMeshSession.EVENT_ENDPOINT_PREFIX}peer-123"

        assertEquals("peer-123", EventMeshSession.eventPeerIdOrNull(endpointName))
        assertNull(EventMeshSession.eventPeerIdOrNull("Alice"))
    }

    @Test
    fun exactlyOnePhoneInitiatesEachEventConnection() {
        assertTrue(EventMeshSession.shouldInitiateEventConnection("alice", "bob"))
        assertFalse(EventMeshSession.shouldInitiateEventConnection("bob", "alice"))
        assertFalse(EventMeshSession.shouldInitiateEventConnection("alice", "alice"))
    }

    @Test
    fun switchingEventsRestartsTheNearbyServiceWithoutLeakingEndpoints() {
        val session = EventMeshSession()
        session.setActiveEvent("event-1", "secret-one", "alice-uid", accessGranted = true)
        session.markEndpoint("endpoint-bob")

        val switched = session.setActiveEvent("event-2", "secret-two", "alice-uid", accessGranted = true)

        assertTrue(switched)
        assertEquals("event-2", session.eventId)
        assertTrue(session.eventEndpoints().isEmpty())
        assertNotEquals(
            EventMeshSession.eventServiceId("event-1", "secret-one"),
            session.currentServiceId(),
        )
    }

    @Test
    fun updatingAccessOnTheSameEventKeepsTheCurrentService() {
        val session = EventMeshSession()
        session.setActiveEvent("event-1", "secret-one", "alice-uid", accessGranted = false)
        val serviceId = session.currentServiceId()
        session.markEndpoint("endpoint-bob")

        val switched = session.setActiveEvent("event-1", "secret-one", "alice-uid", accessGranted = true)

        assertFalse(switched)
        assertTrue(session.accessGranted)
        assertEquals(setOf("endpoint-bob"), session.eventEndpoints())
        assertEquals(serviceId, session.currentServiceId())
    }
}
