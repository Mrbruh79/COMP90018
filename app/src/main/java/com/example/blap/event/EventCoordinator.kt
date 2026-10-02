package com.example.blap.event

import com.example.blap.chat.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EventCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val adminKeyStore: EventAdminKeyStore,
    private val identityStore: IdentityStore,
    private val nearbyController: EventMeshGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _uiState = MutableStateFlow(EventUiState())
    val uiState: StateFlow<EventUiState> = _uiState.asStateFlow()

    private val discussionCoordinator = EventDiscussionCoordinator(
        remoteRepository = remoteRepository,
        scope = scope,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        clock = clock,
    )
    private val onSiteCoordinator = EventOnSiteCoordinator(
        eventStore = eventStore,
        remoteRepository = remoteRepository,
        adminKeyStore = adminKeyStore,
        meshGateway = nearbyController,
        scope = scope,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        closeEventObservers = discussionCoordinator::closeObservers,
        clock = clock,
    )
    private val lifecycleCoordinator = EventLifecycleCoordinator(
        eventStore = eventStore,
        remoteRepository = remoteRepository,
        adminKeyStore = adminKeyStore,
        identityStore = identityStore,
        meshGateway = nearbyController,
        scope = scope,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        closeEventObservers = discussionCoordinator::closeObservers,
        clock = clock,
    )
    private val membershipCoordinator = EventMembershipCoordinator(
        eventStore = eventStore,
        remoteRepository = remoteRepository,
        identityStore = identityStore,
        meshGateway = nearbyController,
        scope = scope,
        requireUserId = lifecycleCoordinator::requireUserId,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        deletePrimaryAdminEvent = lifecycleCoordinator::deleteSelectedEvent,
        closeEventObservers = discussionCoordinator::closeObservers,
        clock = clock,
    )
    private val discoveryCoordinator = EventDiscoveryCoordinator(
        remoteRepository = remoteRepository,
        scope = scope,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        clock = clock,
    )

    init {
        lifecycleCoordinator.start()
    }

    fun accountChanged() {
        discoveryCoordinator.resetForAccount()
        lifecycleCoordinator.accountChanged()
    }

    fun showList() {
        lifecycleCoordinator.closeAnnouncementObserver()
        discussionCoordinator.closeObservers()
        _uiState.update {
            it.copy(
                page = EventPage.LIST,
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
                checkInQrPayload = null,
                error = null,
            )
        }
        refreshEvents()
        discoveryCoordinator.refresh()
    }

    fun beginCreate() {
        _uiState.update { it.copy(page = EventPage.CREATE, error = null) }
    }

    fun beginEdit() = lifecycleCoordinator.beginEdit()

    fun back() {
        when (_uiState.value.page) {
            EventPage.LIST -> Unit
            EventPage.CREATE,
            EventPage.DETAIL,
            -> showList()

            EventPage.EDIT -> _uiState.update { it.copy(page = EventPage.DETAIL, error = null) }

            EventPage.ANNOUNCEMENTS,
            -> {
                lifecycleCoordinator.closeAnnouncementObserver()
                _uiState.update { it.copy(page = EventPage.DETAIL, checkInQrPayload = null, error = null) }
            }

            EventPage.ON_SITE_CHAT,
            -> _uiState.update { it.copy(page = EventPage.DETAIL, checkInQrPayload = null, error = null) }

            EventPage.DISCUSSION -> {
                discussionCoordinator.closeObservers()
                _uiState.update {
                    it.copy(
                        page = EventPage.DETAIL,
                        discussionReplies = emptyList(),
                        selectedDiscussionThreadId = null,
                        error = null,
                    )
                }
            }

            EventPage.DISCUSSION_THREAD -> discussionCoordinator.backToList()
        }
    }

    fun refreshEvents() = lifecycleCoordinator.refreshEvents()

    fun selectEventListSection(section: EventListSection) = discoveryCoordinator.selectSection(section)

    fun updateEventSearchQuery(query: String) = discoveryCoordinator.updateSearchQuery(query)

    fun searchEvents() = discoveryCoordinator.search()

    fun clearEventSearch() = discoveryCoordinator.clearSearch()

    fun discoverEventsInCity(city: String) = discoveryCoordinator.discoverCity(city)

    fun discoverNearbyEvents(coordinates: com.example.blap.location.GeoCoordinates) =
        discoveryCoordinator.discoverNearby(coordinates)

    fun setEventDiscoveryDistance(distanceKm: Int) = discoveryCoordinator.setDistance(distanceKm)

    fun setEventDateFilter(filter: EventDateFilter) = discoveryCoordinator.setDateFilter(filter)

    fun setEventAccessFilter(filter: EventAccessFilter) = discoveryCoordinator.setAccessFilter(filter)

    fun loadMoreDiscoveredEvents() = discoveryCoordinator.loadMore()

    fun createEvent(
        title: String,
        description: String,
        venueName: String,
        latitude: Double,
        longitude: Double,
        radiusMetres: Double,
        startsAt: Long,
        endsAt: Long,
        visibility: EventVisibility,
        requiresSignIn: Boolean,
    ) = lifecycleCoordinator.createEvent(
        title = title,
        description = description,
        venueName = venueName,
        latitude = latitude,
        longitude = longitude,
        radiusMetres = radiusMetres,
        startsAt = startsAt,
        endsAt = endsAt,
        visibility = visibility,
        requiresSignIn = requiresSignIn,
    )

    fun updateSelectedEvent(request: EventCreateRequest) = lifecycleCoordinator.updateSelectedEvent(request)

    fun deleteSelectedEvent() = lifecycleCoordinator.deleteSelectedEvent()

    fun openEvent(eventId: String) = lifecycleCoordinator.openEvent(eventId)

    fun joinSelectedEvent() = membershipCoordinator.joinSelectedEvent()

    fun inviteToSelectedEvent(identifier: String) = membershipCoordinator.invite(identifier)

    fun searchSelectedEventParticipant(identifier: String) = membershipCoordinator.searchParticipant(identifier)

    fun acceptInvitation(invitationId: String) = membershipCoordinator.acceptInvitation(invitationId)

    fun declineInvitation(invitationId: String) = membershipCoordinator.declineInvitation(invitationId)

    fun revokeInvitation(invitationId: String) = membershipCoordinator.revokeInvitation(invitationId)

    fun leaveSelectedEvent() {
        lifecycleCoordinator.closeAnnouncementObserver()
        membershipCoordinator.leaveSelectedEvent()
    }

    fun promoteMemberToCoAdmin(userId: String) = membershipCoordinator.promoteToCoAdmin(userId)

    fun blockMember(userId: String) = membershipCoordinator.blockMember(userId)

    fun showAnnouncements() = lifecycleCoordinator.showAnnouncements()

    fun showDiscussion() = discussionCoordinator.show()

    fun loadMoreDiscussionRoots() = discussionCoordinator.loadMoreRoots()

    fun openDiscussionThread(threadId: String) = discussionCoordinator.openThread(threadId)

    fun createDiscussionComment(text: String, parentId: String? = null) =
        discussionCoordinator.createComment(text, parentId)

    fun toggleDiscussionLike(commentId: String) = discussionCoordinator.toggleLike(commentId)

    fun deleteDiscussionComment(commentId: String) = discussionCoordinator.deleteComment(commentId)

    fun publishAnnouncement(text: String) = lifecycleCoordinator.publishAnnouncement(text)

    fun enterWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) =
        onSiteCoordinator.enterWithGps(latitude, longitude, accuracyMetres)

    fun enterWithQr(payload: String) = onSiteCoordinator.enterWithQr(payload)

    fun createVenueCheckInQr(): String? = onSiteCoordinator.createVenueCheckInQr()

    fun showVenueCheckInQr() = onSiteCoordinator.showVenueCheckInQr()

    fun hideVenueCheckInQr() = onSiteCoordinator.hideVenueCheckInQr()

    fun sendOnSiteMessage(text: String) = onSiteCoordinator.sendMessage(text)

    fun showSavedOnSiteHistory() = onSiteCoordinator.showSavedHistory()

    fun onEventPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) =
        onSiteCoordinator.onPeerAvailable(peerId, eventId, userId, accessGranted)

    fun onEventChatMessageReceived(message: EventChatMessage) =
        onSiteCoordinator.onChatMessageReceived(message)

    fun onEventAnnouncementReceived(announcement: EventAnnouncement) =
        onSiteCoordinator.onAnnouncementReceived(announcement)

    fun onEventMutationReceived(mutation: EventMutation) = onSiteCoordinator.onMutationReceived(mutation)

    fun dismissMessage() {
        _uiState.update { it.copy(error = null, notice = null) }
    }

    fun close() {
        lifecycleCoordinator.close()
        discussionCoordinator.closeObservers()
        eventStore.close()
    }
}
