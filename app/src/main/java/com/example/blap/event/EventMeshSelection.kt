package com.example.blap.event

/** Keeps an event-only service from blocking private chat after leaving the Events tab. */
internal data class EventMeshSelection(val eventId: String, val meshSecret: String, val userId: String) {
    companion object {
        fun select(state: EventUiState, eventsVisible: Boolean, now: Long): EventMeshSelection? {
            if (!eventsVisible) return null
            val event = state.selectedEvent ?: return null
            val membership = state.membership ?: return null
            if (state.activeEventId != event.id || !EventCheckInState.isCheckedIn(event, membership, now)) return null
            return EventMeshSelection(event.id, event.privateMeshSecret, membership.userId)
        }
    }
}
