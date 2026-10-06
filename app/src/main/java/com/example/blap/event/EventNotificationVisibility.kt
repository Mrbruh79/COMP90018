package com.example.blap.event

/** A selected page is visible only while the Activity and the Events destination are visible. */
internal object EventNotificationVisibility {
    fun discussion(state: EventUiState, eventsVisible: Boolean, comment: EventDiscussionComment): Boolean {
        if (!eventsVisible || state.selectedEventId != comment.eventId) return false
        return when (state.page) {
            EventPage.DISCUSSION -> comment.isRoot
            EventPage.DISCUSSION_THREAD -> state.selectedDiscussionThreadId == comment.threadId
            else -> false
        }
    }
}
