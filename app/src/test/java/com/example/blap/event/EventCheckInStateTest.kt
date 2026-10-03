package com.example.blap.event

import org.junit.Assert.*
import org.junit.Test

class EventCheckInStateTest {
    private val event = CommunityEvent("event", "Meetup", "", latitude = 0.0, longitude = 0.0,
        startsAt = 100L, endsAt = 300L, createdBy = "alice")
    private val joined = EventMembership("event", "alice", "Alice", EventRole.PRIMARY_ADMIN, 100L)
    private val checked = joined.copy(accessMethod = EventAccessMethod.GPS, checkedInAt = 150L)

    @Test fun openingAMemberEventRequiresGpsOnlyBeforeCheckIn() {
        val state = EventUiState(page = EventPage.DETAIL, events = listOf(event), selectedEventId = event.id, membership = joined)
        assertTrue(EventCheckInState.needsGps(state, 200L))
        assertFalse(EventCheckInState.needsGps(state.copy(membership = checked), 200L))
        assertFalse(EventCheckInState.needsGps(state.copy(membership = null), 200L))
        assertTrue(EventCheckInState.needsGps(state.copy(page = EventPage.ANNOUNCEMENTS), 200L))
        assertFalse(EventCheckInState.needsGps(state, 301L))
    }

    @Test fun eventSessionPagesShareAdmissionButListAndEditorsDoNotStartIt() {
        val state = EventUiState(events = listOf(event), selectedEventId = event.id, membership = joined)
        val sessionPages = setOf(EventPage.DETAIL, EventPage.ANNOUNCEMENTS, EventPage.ON_SITE_CHAT,
            EventPage.DISCUSSION, EventPage.DISCUSSION_THREAD)
        EventPage.entries.forEach { page ->
            assertEquals(page.name, page in sessionPages, EventCheckInState.needsGps(state.copy(page = page), 200L))
            assertFalse(EventCheckInState.needsGps(state.copy(page = page, membership = checked), 200L))
            assertFalse(EventCheckInState.needsGps(state.copy(page = page, membership = joined.copy(blockedAt = 190L)), 200L))
        }
    }
    @Test fun cloudMembershipRefreshKeepsLocalVenueCheckInAndRemoteRole() {
        val updated = joined.copy(displayName = "New name", role = EventRole.CO_ADMIN)
        assertEquals(updated.copy(accessMethod = EventAccessMethod.GPS, checkedInAt = 150L),
            EventCheckInState.mergeMembership(updated, checked))
    }
    @Test fun rejoiningRemovalOrDifferentMembershipCannotReuseOldCheckIn() {
        assertFalse(EventCheckInState.isCheckedIn(event, checked.copy(leftAt = 190L), 200L))
        assertFalse(EventCheckInState.isCheckedIn(event, checked.copy(blockedAt = 190L), 200L))
        assertFalse(EventCheckInState.isCheckedIn(event, checked, 301L))
        assertFalse(EventCheckInState.isCheckedIn(event, checked.copy(userId = "outsider"), 200L))
        assertEquals(joined.copy(joinedAt = 190L), EventCheckInState.mergeMembership(joined.copy(joinedAt = 190L), checked))
        assertEquals(joined.copy(blockedAt = 190L), EventCheckInState.mergeMembership(joined.copy(blockedAt = 190L), checked))
    }
}
