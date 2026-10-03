package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMeshSelectionTest {
    private val event = CommunityEvent("event", "Meetup", "", latitude = 0.0, longitude = 0.0,
        startsAt = 100L, endsAt = 300L, createdBy = "alice", visibility = EventVisibility.PRIVATE,
        privateMeshSecret = "secret")
    private val membership = EventMembership(eventId = "event", userId = "alice", displayName = "Alice",
        role = EventRole.PRIMARY_ADMIN, joinedAt = 100L, accessMethod = EventAccessMethod.GPS, checkedInAt = 150L)
    private val state = EventUiState(events = listOf(event), selectedEventId = "event", activeEventId = "event",
        membership = membership, page = EventPage.ON_SITE_CHAT)

    @Test fun privateChatUsesTheNormalServiceEvenWithACheckedInEvent() {
        assertNull(EventMeshSelection.select(state, false, 200L))
    }

    @Test fun returningToAValidCheckedInEventResumesItsIsolatedMesh() {
        assertEquals(EventMeshSelection("event", "secret", "alice"), EventMeshSelection.select(state, true, 200L))
    }

    @Test fun announcementsUseTheSameMeshAsOnSiteChat() {
        assertEquals(EventMeshSelection.select(state, true, 200L),
            EventMeshSelection.select(state.copy(page = EventPage.ANNOUNCEMENTS), true, 200L))
        assertNull(EventMeshSelection.select(state.copy(page = EventPage.ANNOUNCEMENTS, activeEventId = null), true, 200L))
    }

    @Test fun savedHistoryOrAnUncheckedMembershipCannotActivateEventMesh() {
        assertNull(EventMeshSelection.select(state.copy(activeEventId = null), true, 200L))
        assertNull(EventMeshSelection.select(state.copy(membership = membership.copy(checkedInAt = null)), true, 200L))
        assertNull(EventMeshSelection.select(state.copy(membership = membership.copy(eventId = "other")), true, 200L))
    }

    @Test fun expiredRemovedOrBlockedMembershipsCannotResume() {
        assertNull(EventMeshSelection.select(state, true, 301L))
        assertNull(EventMeshSelection.select(state.copy(membership = membership.copy(leftAt = 190L)), true, 200L))
        assertNull(EventMeshSelection.select(state.copy(membership = membership.copy(blockedAt = 190L)), true, 200L))
    }
}
