package com.example.blap.event

import org.junit.Assert.*
import org.junit.Test

class EventNotificationTargetTest {
    private val event = CommunityEvent("event", "Meetup", "", latitude = 0.0, longitude = 0.0,
        startsAt = 0, endsAt = 1000, createdBy = "me", memberIds = setOf("me"))

    @Test fun accountBoundNotificationCannotOpenAnotherAccountsEvent() {
        val target = EventNotificationTarget("event", "me")
        assertTrue(target.belongsTo("me"))
        assertFalse(target.belongsTo("other"))
    }

    @Test fun olderNotificationsWithoutAccountExtraRemainSupported() {
        assertTrue(EventNotificationTarget("event").belongsTo("me"))
    }

    @Test fun onlyAvailableJoinedEventsCanBeOpened() {
        val target = EventNotificationTarget("event", "me")
        val state = EventUiState(currentUserId = "me", events = listOf(event))
        assertTrue(target.isAvailable(state))
        assertFalse(target.isAvailable(state.copy(events = emptyList())))
        assertFalse(target.isAvailable(state.copy(events = listOf(event.copy(deletedAt = 10)))))
        assertFalse(target.isAvailable(state.copy(currentUserId = "other")))
    }
}
