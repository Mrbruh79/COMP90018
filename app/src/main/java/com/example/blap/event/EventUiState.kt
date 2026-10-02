package com.example.blap.event

import com.example.blap.location.GeoCoordinates

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
    val eventListSection: EventListSection = EventListSection.DISCOVER,
    val discoveryMode: EventDiscoveryMode = EventDiscoveryMode.UPCOMING,
    val discoveryEventIds: List<String> = emptyList(),
    val discoverySearchQuery: String = "",
    val discoveryCityQuery: String = "",
    val discoveryCentre: GeoCoordinates? = null,
    val discoveryDistanceKm: Int = DEFAULT_DISCOVERY_DISTANCE_KM,
    val discoveryDateFilter: EventDateFilter = EventDateFilter.ANY_UPCOMING,
    val discoveryAccessFilter: EventAccessFilter = EventAccessFilter.ALL,
    val discoveryLoading: Boolean = false,
    val discoveryHasMore: Boolean = false,
    val discoveryLimit: Int = DISCOVERY_PAGE_SIZE,
    val eventInvitations: List<EventInvitation> = emptyList(),
    val participantSearchResult: EventParticipantSearchResult? = null,
    val announcements: List<EventAnnouncement> = emptyList(),
    val chatMessages: List<EventChatMessage> = emptyList(),
    val discussionRoots: List<EventDiscussionComment> = emptyList(),
    val discussionReplies: List<EventDiscussionComment> = emptyList(),
    val likedDiscussionCommentIds: Set<String> = emptySet(),
    val selectedDiscussionThreadId: String? = null,
    val discussionHasMoreRoots: Boolean = false,
    val activeEventId: String? = null,
    val checkInQrPayload: String? = null,
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

    val listedEvents: List<CommunityEvent>
        get() = when (eventListSection) {
            EventListSection.MY_EVENTS -> events.filter { currentUserId in it.memberIds }
            EventListSection.DISCOVER -> discoveryEventIds.mapNotNull { id -> events.firstOrNull { it.id == id } }
        }

    fun currentEventsForList(now: Long): List<CommunityEvent> = when (eventListSection) {
        EventListSection.DISCOVER -> listedEvents.filter { !it.isDeleted && it.endsAt >= now }
        EventListSection.MY_EVENTS -> listedEvents
            .filter { !it.isDeleted && it.endsAt >= now }
            .sortedBy(CommunityEvent::startsAt)
    }

    fun pastJoinedEvents(now: Long): List<CommunityEvent> = if (eventListSection == EventListSection.MY_EVENTS) {
        events.asSequence()
            .filter { currentUserId in it.memberIds && !it.isDeleted && it.endsAt < now }
            .sortedByDescending(CommunityEvent::endsAt)
            .toList()
    } else {
        emptyList()
    }
}

internal fun List<CommunityEvent>.upsertEvent(event: CommunityEvent): List<CommunityEvent> =
    (filterNot { it.id == event.id } + event).sortedBy(CommunityEvent::startsAt)

internal fun List<CommunityEvent>.withoutEvent(eventId: String): List<CommunityEvent> =
    filterNot { it.id == eventId }

/** Returns to the event list without discarding the user's active discovery filters/results. */
internal fun EventUiState.returnToEventList(
    events: List<CommunityEvent> = this.events,
    notice: String? = null,
    showingOfflineEvents: Boolean = this.showingOfflineEvents,
): EventUiState = copy(
    page = EventPage.LIST,
    events = events,
    selectedEventId = null,
    membership = null,
    members = emptyList(),
    eventInvitations = emptyList(),
    participantSearchResult = null,
    announcements = emptyList(),
    chatMessages = emptyList(),
    discussionRoots = emptyList(),
    discussionReplies = emptyList(),
    likedDiscussionCommentIds = emptySet(),
    selectedDiscussionThreadId = null,
    discussionHasMoreRoots = false,
    activeEventId = null,
    checkInQrPayload = null,
    showingOfflineEvents = showingOfflineEvents,
    loading = false,
    notice = notice,
    error = null,
)
