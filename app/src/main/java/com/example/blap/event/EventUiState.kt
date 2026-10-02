package com.example.blap.event

enum class EventPage {
    LIST,
    CREATE,
    EDIT,
    DETAIL,
    ANNOUNCEMENTS,
    ON_SITE_CHAT,
    DISCUSSION,
    DISCUSSION_THREAD,
}

data class EventUiState(
    val page: EventPage = EventPage.LIST,
    val events: List<CommunityEvent> = emptyList(),
    val selectedEventId: String? = null,
    val membership: EventMembership? = null,
    val members: List<EventMembership> = emptyList(),
    val invitations: List<EventInvitation> = emptyList(),
    val eventInvitations: List<EventInvitation> = emptyList(),
    val participantSearchResult: EventParticipantSearchResult? = null,
    val accessRequests: List<EventAccessRequest> = emptyList(),
    val announcements: List<EventAnnouncement> = emptyList(),
    val chatMessages: List<EventChatMessage> = emptyList(),
    val discussionRoots: List<EventDiscussionComment> = emptyList(),
    val discussionReplies: List<EventDiscussionComment> = emptyList(),
    val likedDiscussionCommentIds: Set<String> = emptySet(),
    val selectedDiscussionThreadId: String? = null,
    val discussionHasMoreRoots: Boolean = false,
    val activeEventId: String? = null,
    val checkInQrPayload: String? = null,
    val waitingForAdminAccess: Boolean = false,
    val showingOfflineEvents: Boolean = false,
    val currentUserId: String = "",
    val loading: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val selectedEvent: CommunityEvent?
        get() = events.firstOrNull { it.id == selectedEventId }

    val selectedDiscussionRoot: EventDiscussionComment?
        get() = discussionRoots.firstOrNull { it.id == selectedDiscussionThreadId }
}

internal fun List<CommunityEvent>.upsertEvent(event: CommunityEvent): List<CommunityEvent> =
    (filterNot { it.id == event.id } + event).sortedBy(CommunityEvent::startsAt)

internal fun List<CommunityEvent>.withoutEvent(eventId: String): List<CommunityEvent> =
    filterNot { it.id == eventId }
