package com.example.blap.event

import org.junit.Assert.assertTrue
import org.junit.Test

class EventAccessPolicyTest {
    private val now = 1_000_000L
    private val event = CommunityEvent(
        id = "event-1",
        title = "CommonGround Live",
        description = "Test event",
        latitude = -37.8136,
        longitude = 144.9631,
        radiusMetres = 100.0,
        startsAt = now - 1_000,
        endsAt = now + 1_000,
        createdBy = "admin-1",
    )
    private val membership = EventMembership(
        eventId = event.id,
        userId = "attendee-1",
        displayName = "Alice",
        role = EventRole.ATTENDEE,
        joinedAt = now - 2_000,
    )

    @Test
    fun accurateLocationInsideRadiusAllowsEntry() {
        val result = EventAccessPolicy.evaluateGps(
            event,
            membership,
            currentLatitude = -37.8136,
            currentLongitude = 144.9631,
            accuracyMetres = 12.0,
            now = now,
        )

        assertTrue(result is EventEntryDecision.Allowed)
    }

    @Test
    fun inaccurateLocationRequiresQr() {
        val result = EventAccessPolicy.evaluateGps(
            event,
            membership,
            currentLatitude = -37.8136,
            currentLongitude = 144.9631,
            accuracyMetres = 90.0,
            now = now,
        )

        assertTrue(result is EventEntryDecision.NeedsQr)
    }

    @Test
    fun chatBecomesReadOnlyAfterEventEnds() {
        val result = EventAccessPolicy.evaluateGps(
            event,
            membership,
            currentLatitude = -37.8136,
            currentLongitude = 144.9631,
            accuracyMetres = 10.0,
            now = event.endsAt + 1,
        )

        assertTrue(result is EventEntryDecision.Denied)
        assertTrue((result as EventEntryDecision.Denied).reason.contains("ended"))
    }

    @Test
    fun blockedMemberCannotEnter() {
        val result = EventAccessPolicy.evaluateGps(
            event,
            membership.copy(blockedAt = now - 1),
            currentLatitude = -37.8136,
            currentLongitude = 144.9631,
            accuracyMetres = 10.0,
            now = now,
        )

        assertTrue(result is EventEntryDecision.Denied)
    }

    @Test
    fun deletedEventCannotBeEntered() {
        val result = EventAccessPolicy.evaluateGps(
            event.copy(deletedAt = now),
            membership,
            currentLatitude = -37.8136,
            currentLongitude = 144.9631,
            accuracyMetres = 10.0,
            now = now,
        )

        assertTrue(result is EventEntryDecision.Denied)
        assertTrue((result as EventEntryDecision.Denied).reason.contains("deleted"))
    }
}
