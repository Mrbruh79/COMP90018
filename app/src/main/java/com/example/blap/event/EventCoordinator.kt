package com.example.blap.event

import com.example.blap.chat.IdentityStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

class EventCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val adminKeyStore: EventAdminKeyStore,
    private val identityStore: IdentityStore,
    private val nearbyController: EventMeshGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _uiState = MutableStateFlow(EventUiState(events = eventStore.getEvents()))
    val uiState: StateFlow<EventUiState> = _uiState.asStateFlow()

    private var cachedUserId: String? = null
    private var createRequestInFlight = false
    private var eventMutationInFlight = false
    private val tombstoneCleanupInFlight = mutableSetOf<String>()
    private var eventObserver: AutoCloseable? = null
    private var invitationObserver: AutoCloseable? = null
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
        identityStore = identityStore,
        meshGateway = nearbyController,
        scope = scope,
        currentState = { _uiState.value },
        updateState = { transform -> _uiState.update(transform) },
        closeEventObservers = discussionCoordinator::closeObservers,
        clock = clock,
    )

    init {
        scope.launch {
            runCatching {
                val userId = requireUserId()
                _uiState.update { it.copy(currentUserId = userId) }
                startEventObserver()
                startInvitationObserver()
            }.onFailure { failure ->
                _uiState.update { state ->
                    state.copy(error = failure.readableMessage("Could not connect to events"))
                }
            }
        }
    }

    @Synchronized
    private fun startInvitationObserver() {
        if (invitationObserver != null) return
        invitationObserver = remoteRepository.observeInvitations(
            onInvitations = { invitations ->
                _uiState.update { state ->
                    state.copy(
                        invitations = invitations.filter { it.status == EventInvitationStatus.PENDING }
                            .sortedBy(EventInvitation::startsAt),
                    )
                }
            },
            onError = { failure ->
                _uiState.update { it.copy(notice = failure.readableMessage("Invitations will refresh when online")) }
            },
        )
    }

    @Synchronized
    private fun startEventObserver() {
        if (eventObserver != null) return
        eventObserver = remoteRepository.observeEvents(
            onEvents = { remoteEvents, authoritative ->
            val tombstones = remoteEvents.filter(CommunityEvent::isDeleted)
            val activeRemoteEvents = remoteEvents.filterNot(CommunityEvent::isDeleted)
            val remoteIds = activeRemoteEvents.mapTo(mutableSetOf(), CommunityEvent::id)
            val removedIds = if (authoritative) {
                eventStore.getEvents().map(CommunityEvent::id).filterNot(remoteIds::contains).toSet()
            } else {
                tombstones.mapTo(mutableSetOf(), CommunityEvent::id)
            }
            activeRemoteEvents.forEach(eventStore::saveEvent)
            removedIds.forEach(eventStore::purgeEvent)
            if (_uiState.value.activeEventId in removedIds) nearbyController.setActiveEvent(null)
            if (_uiState.value.selectedEventId in removedIds) discussionCoordinator.closeObservers()
            _uiState.update { state ->
                if (state.selectedEventId in removedIds) {
                    EventUiState(
                        events = eventStore.getEvents(),
                        notice = "The event was deleted by its primary admin.",
                    )
                } else {
                    state.copy(events = eventStore.getEvents(), loading = false)
                }
            }
            if (authoritative) tombstones.forEach(::finishLegacyDeletion)
        },
            onError = { failure ->
                _uiState.update { state ->
                    if (state.events.isEmpty()) {
                        state.copy(error = failure.readableMessage("Events could not be updated"))
                    } else {
                        state.copy(notice = "Event updates will resume when you are online.")
                    }
                }
            },
        )
    }

    fun accountChanged() {
        eventObserver?.close()
        invitationObserver?.close()
        discussionCoordinator.closeObservers()
        eventObserver = null
        invitationObserver = null
        cachedUserId = null
        scope.launch {
            runCatching {
                val userId = requireUserId()
                _uiState.update { it.copy(currentUserId = userId) }
                startEventObserver()
                startInvitationObserver()
                refreshEvents()
            }.onFailure { failure ->
                _uiState.update { it.copy(error = failure.readableMessage("Could not refresh events")) }
            }
        }
    }

    fun showList() {
        discussionCoordinator.closeObservers()
        _uiState.update {
            it.copy(
                page = EventPage.LIST,
                selectedEventId = null,
                membership = null,
                members = emptyList(),
                eventInvitations = emptyList(),
                participantSearchResult = null,
                accessRequests = emptyList(),
                announcements = emptyList(),
                chatMessages = emptyList(),
                discussionRoots = emptyList(),
                discussionReplies = emptyList(),
                likedDiscussionCommentIds = emptySet(),
                selectedDiscussionThreadId = null,
                discussionHasMoreRoots = false,
                checkInQrPayload = null,
                waitingForAdminAccess = false,
                error = null,
            )
        }
        refreshEvents()
    }

    fun beginCreate() {
        _uiState.update { it.copy(page = EventPage.CREATE, error = null) }
    }

    fun beginEdit() {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || event.isDeleted) {
            _uiState.update { it.copy(error = "Only an active event admin can edit this event.") }
            return
        }
        _uiState.update { it.copy(page = EventPage.EDIT, error = null) }
    }

    fun back() {
        when (_uiState.value.page) {
            EventPage.LIST -> Unit
            EventPage.CREATE,
            EventPage.DETAIL,
            -> showList()

            EventPage.EDIT -> _uiState.update { it.copy(page = EventPage.DETAIL, error = null) }

            EventPage.ANNOUNCEMENTS,
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

    fun refreshEvents() {
        _uiState.update { it.copy(events = eventStore.getEvents(), loading = true, error = null) }
        scope.launch {
            runCatching {
                requireUserId()
                startEventObserver()
                remoteRepository.listEvents()
            }
                .onSuccess { remoteEvents ->
                    remoteEvents.forEach(eventStore::saveEvent)
                    val invitations = runCatching { remoteRepository.listInvitations() }.getOrDefault(emptyList())
                    _uiState.update {
                        it.copy(
                            events = eventStore.getEvents(),
                            invitations = invitations.filter { invitation ->
                                invitation.status == EventInvitationStatus.PENDING
                            },
                            loading = false,
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            notice = if (it.events.isEmpty()) null else "Showing cached events.",
                            error = if (it.events.isEmpty()) failure.readableMessage("Events could not be loaded") else null,
                        )
                    }
                }
        }
    }

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
    ) {
        if (createRequestInFlight) return
        val cleanTitle = title.trim().take(80)
        val cleanDescription = description.trim().take(1_000)
        if (cleanTitle.isBlank()) {
            _uiState.update { it.copy(error = "Enter an event title.") }
            return
        }
        if (endsAt <= startsAt || endsAt <= clock()) {
            _uiState.update { it.copy(error = "Choose an end time after the start time.") }
            return
        }
        if (!remoteRepository.hasSignedInAccount()) {
            _uiState.update { it.copy(error = "Sign in with Email or Google to create an event.") }
            return
        }
        createRequestInFlight = true
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching {
                    val userId = requireUserId()
                    val keys = adminKeyStore.getOrCreate(userId)
                    val eventId = UUID.randomUUID().toString()
                    val venueCheckInPayload = if (visibility == EventVisibility.PUBLIC) {
                        EventCheckInCodec.create(eventId, userId, keys.private)
                    } else ""
                    val event = CommunityEvent(
                        id = eventId,
                        title = cleanTitle,
                        description = cleanDescription,
                        venueName = venueName.trim().take(200),
                        latitude = latitude,
                        longitude = longitude,
                        radiusMetres = radiusMetres,
                        startsAt = startsAt,
                        endsAt = endsAt,
                        createdBy = userId,
                        adminIds = setOf(userId),
                        memberIds = setOf(userId),
                        adminPublicKeys = mapOf(userId to EventCheckInCodec.encodePublicKey(keys.public)),
                        visibility = visibility,
                        requiresSignIn = visibility == EventVisibility.PUBLIC && requiresSignIn,
                        privateMeshSecret = if (visibility == EventVisibility.PRIVATE) {
                            EventSecrets.newMeshSecret()
                        } else "",
                        venueCheckInPayload = venueCheckInPayload,
                        createdAt = clock(),
                    )
                    val membership = EventMembership(
                        eventId = event.id,
                        userId = userId,
                        displayName = identityStore.getDisplayName(),
                        role = EventRole.PRIMARY_ADMIN,
                        joinedAt = clock(),
                    )
                    remoteRepository.createEvent(event, membership)
                    eventStore.saveEvent(event)
                    eventStore.saveMembership(membership)
                    event to membership
                }.onSuccess { (event, membership) ->
                    _uiState.update {
                        it.copy(
                            page = EventPage.DETAIL,
                            events = eventStore.getEvents(),
                            selectedEventId = event.id,
                            membership = membership,
                            loading = false,
                            notice = "Event created.",
                        )
                    }
                }.onFailure { failure ->
                    _uiState.update {
                        it.copy(loading = false, error = failure.readableMessage("Event could not be created"))
                    }
                }
            } finally {
                createRequestInFlight = false
            }
        }
    }

    fun updateSelectedEvent(request: EventCreateRequest) {
        if (eventMutationInFlight) return
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || event.isDeleted) {
            _uiState.update { it.copy(error = "Only an active event admin can edit this event.") }
            return
        }
        val cleanTitle = request.title.trim().take(80)
        if (cleanTitle.isBlank() || request.venueName.isBlank()) {
            _uiState.update { it.copy(error = "Add an event title and location.") }
            return
        }
        if (request.endsAt <= request.startsAt || request.endsAt <= clock()) {
            _uiState.update { it.copy(error = "Choose an end time after the start time.") }
            return
        }
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            _uiState.update { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val updated = event.copy(
            title = cleanTitle,
            description = request.description.trim().take(1_000),
            venueName = request.venueName.trim().take(200),
            latitude = request.latitude,
            longitude = request.longitude,
            radiusMetres = request.radiusMetres,
            startsAt = request.startsAt,
            endsAt = request.endsAt,
            visibility = event.visibility,
            requiresSignIn = event.visibility == EventVisibility.PUBLIC && request.requiresSignIn,
            privateMeshSecret = event.privateMeshSecret,
            updatedAt = maxOf(clock(), event.updatedAt + 1),
        )
        val unsigned = EventMutation(updated, membership.userId)
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, keys.private))
        eventMutationInFlight = true
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching { remoteRepository.updateEvent(updated) }
                    .onSuccess {
                        eventStore.saveEvent(updated)
                        nearbyController.sendEventMutation(mutation)
                        _uiState.update {
                            it.copy(
                                page = EventPage.DETAIL,
                                events = eventStore.getEvents(),
                                loading = false,
                                notice = "Event updated for all attendees.",
                            )
                        }
                    }
                    .onFailure { failure ->
                        _uiState.update {
                            it.copy(loading = false, error = failure.readableMessage("Event could not be updated"))
                        }
                    }
            } finally {
                eventMutationInFlight = false
            }
        }
    }

    fun deleteSelectedEvent() {
        if (eventMutationInFlight) return
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        if (membership.role != EventRole.PRIMARY_ADMIN || event.createdBy != membership.userId) {
            _uiState.update { it.copy(error = "Only the primary admin can delete this event.") }
            return
        }
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            _uiState.update { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val deletedAt = event.deletedAt ?: clock()
        val deleted = event.copy(
            deletedAt = deletedAt,
            updatedAt = maxOf(clock(), event.updatedAt + 1),
        )
        val unsigned = EventMutation(deleted, membership.userId)
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, keys.private))
        eventMutationInFlight = true
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching { remoteRepository.deleteEvent(event.id) }
                    .onSuccess {
                        discussionCoordinator.closeObservers()
                        nearbyController.sendEventMutation(mutation)
                        nearbyController.setActiveEvent(null)
                        eventStore.purgeEvent(event.id)
                        _uiState.update {
                            EventUiState(
                                events = eventStore.getEvents(),
                                notice = "Event and all associated data were permanently deleted.",
                            )
                        }
                    }
                    .onFailure { failure ->
                        _uiState.update {
                            it.copy(loading = false, error = failure.readableMessage("Event could not be deleted"))
                        }
                    }
            } finally {
                eventMutationInFlight = false
            }
        }
    }

    fun openEvent(eventId: String) {
        val event = eventStore.getEvent(eventId) ?: return
        discussionCoordinator.closeObservers()
        _uiState.update {
            it.copy(
                page = EventPage.DETAIL,
                selectedEventId = event.id,
                announcements = eventStore.getAnnouncements(event.id),
                chatMessages = eventStore.getChatMessages(event.id),
                participantSearchResult = null,
                discussionRoots = emptyList(),
                discussionReplies = emptyList(),
                likedDiscussionCommentIds = emptySet(),
                selectedDiscussionThreadId = null,
                discussionHasMoreRoots = false,
                error = null,
            )
        }
        scope.launch {
            val userId = runCatching { requireUserId() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(membership = eventStore.getMembership(event.id, userId)) }
            refreshMembers(event.id)
            refreshAnnouncements(event.id)
            if (event.visibility == EventVisibility.PRIVATE && event.isAdmin(userId)) {
                refreshEventInvitations(event.id)
            }
        }
    }

    fun joinSelectedEvent() {
        val event = _uiState.value.selectedEvent ?: return
        if (event.visibility == EventVisibility.PRIVATE) {
            _uiState.update { it.copy(error = "Private events can only be joined by accepting an invitation.") }
            return
        }
        if (event.requiresSignIn && !remoteRepository.hasSignedInAccount()) {
            _uiState.update { it.copy(error = "Sign in with Email or Google to join this protected event.") }
            return
        }
        if (event.isDeleted) {
            _uiState.update { it.copy(error = "This event has been deleted by its admin.") }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val userId = requireUserId()
                val existing = eventStore.getMembership(event.id, userId)
                val membership = EventMembership(
                    eventId = event.id,
                    userId = userId,
                    displayName = identityStore.getDisplayName(),
                    role = existing?.role ?: EventRole.ATTENDEE,
                    joinedAt = existing?.joinedAt ?: clock(),
                )
                eventStore.saveMembership(membership)
                remoteRepository.joinEvent(membership)
                membership
            }.onSuccess { membership ->
                _uiState.update { it.copy(membership = membership, loading = false, notice = "Joined event.") }
            }.onFailure { failure ->
                _uiState.update { it.copy(loading = false, error = failure.readableMessage("Could not join event")) }
            }
        }
    }

    fun inviteToSelectedEvent(identifier: String) {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        if (event.visibility != EventVisibility.PRIVATE || !membership.isAdmin || !event.isAdmin(membership.userId)) {
            _uiState.update { it.copy(error = "Only a private-event admin can send invitations.") }
            return
        }
        if (identifier.isBlank()) return
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val invitee = remoteRepository.findInvitee(identifier)
                    ?: throw IllegalArgumentException("No CommonGround account matched that exact username or verified email.")
                if (invitee.uid in event.memberIds) {
                    throw IllegalArgumentException("That account is already an event member.")
                }
                val invitation = EventInvitation(
                    id = "${event.id}_${invitee.uid}",
                    eventId = event.id,
                    eventTitle = event.title,
                    inviterUid = membership.userId,
                    inviterName = membership.displayName,
                    recipientUid = invitee.uid,
                    recipientName = invitee.displayName,
                    recipientUsername = invitee.username,
                    startsAt = event.startsAt,
                    endsAt = event.endsAt,
                    createdAt = clock(),
                    expiresAt = event.endsAt,
                )
                remoteRepository.invite(invitation)
                invitation
            }.onSuccess { invitation ->
                _uiState.update {
                    it.copy(
                        eventInvitations = (it.eventInvitations.filterNot { old -> old.id == invitation.id } + invitation)
                            .sortedBy(EventInvitation::recipientName),
                        loading = false,
                        notice = "Invitation sent to @${invitation.recipientUsername}.",
                    )
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(loading = false, error = failure.readableMessage("Invitation could not be sent")) }
            }
        }
    }

    fun searchSelectedEventParticipant(identifier: String) {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        if (!localMembership.isAdmin || !event.isAdmin(localMembership.userId)) {
            _uiState.update { it.copy(error = "Only event admins can search participants.") }
            return
        }
        if (identifier.isBlank()) return
        _uiState.update { it.copy(loading = true, error = null, participantSearchResult = null) }
        scope.launch {
            runCatching {
                val account = remoteRepository.findInvitee(identifier)
                    ?: throw IllegalArgumentException(
                        "No CommonGround account matched that exact username or verified email.",
                    )
                val members = remoteRepository.listMembers(event.id)
                val target = members.firstOrNull { member ->
                    member.userId == account.uid && member.canParticipate
                } ?: throw IllegalArgumentException("That account is not an active participant in this event.")
                if (target.role == EventRole.PRIMARY_ADMIN || target.userId == localMembership.userId) {
                    throw IllegalArgumentException("The primary admin cannot be removed.")
                }
                members to EventParticipantSearchResult(target, account.username)
            }.onSuccess { (members, result) ->
                _uiState.update {
                    it.copy(
                        members = members,
                        participantSearchResult = result,
                        loading = false,
                        notice = "Participant found.",
                    )
                }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(
                        participantSearchResult = null,
                        loading = false,
                        error = failure.readableMessage("Participant search failed"),
                    )
                }
            }
        }
    }

    fun acceptInvitation(invitationId: String) {
        val invitation = _uiState.value.invitations.firstOrNull { it.id == invitationId } ?: return
        if (!invitation.isPending) {
            _uiState.update { it.copy(error = "This invitation is no longer available.") }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val userId = requireUserId()
                require(userId == invitation.recipientUid)
                val membership = EventMembership(
                    eventId = invitation.eventId,
                    userId = userId,
                    displayName = identityStore.getDisplayName(),
                    role = EventRole.ATTENDEE,
                    joinedAt = clock(),
                )
                remoteRepository.acceptInvitation(invitation, membership)
                eventStore.saveMembership(membership)
                remoteRepository.listEvents().forEach(eventStore::saveEvent)
                membership
            }.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        events = eventStore.getEvents(),
                        invitations = state.invitations.filterNot { it.id == invitationId },
                        loading = false,
                        notice = "Private event invitation accepted.",
                    )
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(loading = false, error = failure.readableMessage("Invitation could not be accepted")) }
            }
        }
    }

    fun declineInvitation(invitationId: String) {
        val invitation = _uiState.value.invitations.firstOrNull { it.id == invitationId } ?: return
        scope.launch {
            runCatching { remoteRepository.declineInvitation(invitation) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            invitations = state.invitations.filterNot { it.id == invitationId },
                            notice = "Invitation declined.",
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage("Invitation could not be declined")) }
                }
        }
    }

    fun revokeInvitation(invitationId: String) {
        val invitation = _uiState.value.eventInvitations.firstOrNull { it.id == invitationId } ?: return
        scope.launch {
            runCatching { remoteRepository.revokeInvitation(invitation) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            eventInvitations = state.eventInvitations.map {
                                if (it.id == invitationId) it.copy(status = EventInvitationStatus.REVOKED) else it
                            },
                            notice = "Invitation revoked.",
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage("Invitation could not be revoked")) }
                }
        }
    }

    fun leaveSelectedEvent() {
        val event = _uiState.value.selectedEvent ?: return
        val membership = _uiState.value.membership ?: return
        if (membership.role == EventRole.PRIMARY_ADMIN && event.createdBy == membership.userId) {
            deleteSelectedEvent()
            return
        }
        val leftAt = clock()
        val updated = membership.copy(leftAt = leftAt, checkedInAt = null, accessMethod = null)
        eventStore.saveMembership(updated)
        nearbyController.setActiveEvent(null)
        _uiState.update {
            it.copy(membership = updated, activeEventId = null, page = EventPage.DETAIL, notice = "You left the event.")
        }
        scope.launch {
            runCatching { remoteRepository.leaveEvent(event.id, membership.userId, leftAt) }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage("Leaving could not be synced")) }
                }
        }
    }

    fun promoteMemberToCoAdmin(userId: String) {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        if (localMembership.role != EventRole.PRIMARY_ADMIN || event.createdBy != localMembership.userId) {
            _uiState.update { it.copy(error = "Only the primary admin can appoint co-admins.") }
            return
        }
        val target = state.members.firstOrNull { it.userId == userId && it.canParticipate } ?: return
        scope.launch {
            runCatching { remoteRepository.promoteToCoAdmin(event.id, userId) }
                .onSuccess {
                    val updatedEvent = event.copy(adminIds = event.adminIds + userId)
                    eventStore.saveEvent(updatedEvent)
                    _uiState.update {
                        it.copy(
                            events = eventStore.getEvents(),
                            members = it.members.map { member ->
                                if (member.userId == userId) member.copy(role = EventRole.CO_ADMIN) else member
                            },
                            notice = "${target.displayName} is now a co-admin.",
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage("Co-admin could not be added")) }
                }
        }
    }

    fun blockMember(userId: String) {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        if (!localMembership.isAdmin || !event.isAdmin(localMembership.userId)) {
            _uiState.update { it.copy(error = "Only event admins can remove members.") }
            return
        }
        val target = state.members.firstOrNull { it.userId == userId } ?: return
        if (target.role == EventRole.PRIMARY_ADMIN || target.userId == localMembership.userId) {
            _uiState.update { it.copy(error = "The primary admin cannot be removed.") }
            return
        }
        val blockedAt = clock()
        scope.launch {
            runCatching { remoteRepository.blockMember(event.id, userId, blockedAt) }
                .onSuccess {
                    val updatedEvent = event.copy(
                        adminIds = event.adminIds - userId,
                        memberIds = event.memberIds - userId,
                    )
                    eventStore.saveEvent(updatedEvent)
                    _uiState.update {
                        it.copy(
                            events = eventStore.getEvents(),
                            members = it.members.map { member ->
                                if (member.userId == userId) member.copy(blockedAt = blockedAt) else member
                            },
                            participantSearchResult = it.participantSearchResult
                                ?.takeUnless { result -> result.membership.userId == userId },
                            notice = "${target.displayName} was removed from the event.",
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = failure.readableMessage("Member could not be removed")) }
                }
        }
    }

    fun deleteSelectedEventData() {
        val event = _uiState.value.selectedEvent ?: return
        scope.launch {
            val userId = cachedUserId ?: runCatching { requireUserId() }.getOrNull() ?: return@launch
            eventStore.deleteLocalEventData(event.id, userId)
            nearbyController.setActiveEvent(null)
            _uiState.update {
                EventUiState(events = eventStore.getEvents(), notice = "Local event data deleted.")
            }
        }
    }

    fun showAnnouncements() {
        val event = _uiState.value.selectedEvent ?: return
        _uiState.update {
            it.copy(
                page = EventPage.ANNOUNCEMENTS,
                announcements = eventStore.getAnnouncements(event.id),
                error = null,
            )
        }
        refreshAnnouncements(event.id)
    }

    fun showDiscussion() = discussionCoordinator.show()

    fun loadMoreDiscussionRoots() = discussionCoordinator.loadMoreRoots()

    fun openDiscussionThread(threadId: String) = discussionCoordinator.openThread(threadId)

    fun createDiscussionComment(text: String, parentId: String? = null) =
        discussionCoordinator.createComment(text, parentId)

    fun toggleDiscussionLike(commentId: String) = discussionCoordinator.toggleLike(commentId)

    fun deleteDiscussionComment(commentId: String) = discussionCoordinator.deleteComment(commentId)

    fun publishAnnouncement(text: String) {
        val event = _uiState.value.selectedEvent ?: return
        val membership = _uiState.value.membership ?: return
        val cleanText = text.trim().take(1_000)
        if (event.isDeleted) {
            _uiState.update { it.copy(error = "Deleted events are read-only.") }
            return
        }
        if (!membership.isAdmin || !event.isAdmin(membership.userId)) {
            _uiState.update { it.copy(error = "Only event admins can post announcements.") }
            return
        }
        if (cleanText.isBlank()) return
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            _uiState.update { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val unsigned = EventAnnouncement(
            eventId = event.id,
            adminId = membership.userId,
            adminName = membership.displayName,
            text = cleanText,
            createdAt = clock(),
        )
        val announcement = unsigned.copy(signature = EventAnnouncementSigner.sign(unsigned, keys.private))
        eventStore.saveAnnouncement(announcement)
        nearbyController.sendEventAnnouncement(announcement)
        _uiState.update { it.copy(announcements = eventStore.getAnnouncements(event.id)) }
        scope.launch {
            runCatching { remoteRepository.saveAnnouncement(announcement) }
                .onSuccess {
                    eventStore.markAnnouncementSynced(announcement.id)
                    _uiState.update { it.copy(announcements = eventStore.getAnnouncements(event.id)) }
                }
                .onFailure {
                    _uiState.update { it.copy(notice = "Announcement sent on-site and will upload when retried online.") }
                }
        }
    }

    fun enterWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) =
        onSiteCoordinator.enterWithGps(latitude, longitude, accuracyMetres)

    fun enterWithQr(payload: String) = onSiteCoordinator.enterWithQr(payload)

    fun createVenueCheckInQr(): String? = onSiteCoordinator.createVenueCheckInQr()

    fun showVenueCheckInQr() = onSiteCoordinator.showVenueCheckInQr()

    fun hideVenueCheckInQr() = onSiteCoordinator.hideVenueCheckInQr()

    fun sendOnSiteMessage(text: String) = onSiteCoordinator.sendMessage(text)

    fun showSavedOnSiteHistory() = onSiteCoordinator.showSavedHistory()

    fun requestAdminOnSiteAccess() = onSiteCoordinator.requestAdminAccess()

    fun approveOnSiteAccess(requestId: String) = onSiteCoordinator.approveAccess(requestId)

    fun onEventPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) =
        onSiteCoordinator.onPeerAvailable(peerId, eventId, userId, accessGranted)

    fun onEventAccessRequestReceived(request: EventAccessRequest) =
        onSiteCoordinator.onAccessRequestReceived(request)

    fun onEventAccessGrantReceived(grant: EventAccessGrant) =
        onSiteCoordinator.onAccessGrantReceived(grant)

    fun onEventChatMessageReceived(message: EventChatMessage) =
        onSiteCoordinator.onChatMessageReceived(message)

    fun onEventAnnouncementReceived(announcement: EventAnnouncement) =
        onSiteCoordinator.onAnnouncementReceived(announcement)

    fun onEventMutationReceived(mutation: EventMutation) = onSiteCoordinator.onMutationReceived(mutation)

    fun dismissMessage() {
        _uiState.update { it.copy(error = null, notice = null) }
    }

    fun close() {
        eventObserver?.close()
        invitationObserver?.close()
        discussionCoordinator.closeObservers()
        eventStore.close()
    }

    private fun finishLegacyDeletion(event: CommunityEvent) {
        if (!tombstoneCleanupInFlight.add(event.id)) return
        scope.launch {
            try {
                val userId = runCatching { requireUserId() }.getOrNull()
                if (userId == event.createdBy) {
                    runCatching { remoteRepository.deleteEvent(event.id) }
                        .onFailure { failure ->
                            _uiState.update {
                                it.copy(error = failure.readableMessage("An older deleted event could not be cleaned up"))
                            }
                        }
                }
            } finally {
                tombstoneCleanupInFlight.remove(event.id)
            }
        }
    }

    private fun refreshAnnouncements(eventId: String) {
        scope.launch {
            runCatching { remoteRepository.getAnnouncements(eventId) }
                .onSuccess { remote ->
                    remote.forEach(eventStore::saveAnnouncement)
                    eventStore.getPendingAnnouncements(eventId).forEach { pending ->
                        runCatching { remoteRepository.saveAnnouncement(pending) }
                            .onSuccess { eventStore.markAnnouncementSynced(pending.id) }
                    }
                    _uiState.update { state ->
                        if (state.selectedEventId == eventId) {
                            state.copy(announcements = eventStore.getAnnouncements(eventId))
                        } else state
                    }
                }
        }
    }

    private fun refreshEventInvitations(eventId: String) {
        scope.launch {
            runCatching { remoteRepository.listEventInvitations(eventId) }
                .onSuccess { invitations ->
                    _uiState.update { state ->
                        if (state.selectedEventId == eventId) {
                            state.copy(eventInvitations = invitations.sortedBy(EventInvitation::recipientName))
                        } else state
                    }
                }
        }
    }

    private fun refreshMembers(eventId: String) {
        scope.launch {
            runCatching { remoteRepository.listMembers(eventId) }
                .onSuccess { members ->
                    val localUserId = cachedUserId
                    val localMembership = members.firstOrNull { it.userId == localUserId }
                    val cachedMembership = localUserId?.let { eventStore.getMembership(eventId, it) }
                    if (localMembership != null) {
                        eventStore.saveMembership(localMembership)
                        if (localMembership.isAdmin) registerLocalAdminKey(eventId, localMembership.userId)
                    } else if (
                        cachedMembership?.canParticipate == true &&
                        eventStore.getEvent(eventId)?.visibility == EventVisibility.PUBLIC
                    ) {
                        runCatching { remoteRepository.joinEvent(cachedMembership) }
                    }
                    _uiState.update { state ->
                        if (state.selectedEventId == eventId) {
                            state.copy(
                                members = members,
                                membership = localMembership ?: state.membership,
                            )
                        } else state
                    }
                }
        }
    }

    private suspend fun registerLocalAdminKey(eventId: String, userId: String) {
        val event = eventStore.getEvent(eventId) ?: return
        if (userId !in event.adminIds || userId in event.adminPublicKeys) return
        val keys = adminKeyStore.getOrCreate(userId)
        val encoded = EventCheckInCodec.encodePublicKey(keys.public)
        runCatching { remoteRepository.registerAdminPublicKey(eventId, userId, encoded) }
            .onSuccess {
                eventStore.saveEvent(event.copy(adminPublicKeys = event.adminPublicKeys + (userId to encoded)))
                _uiState.update { it.copy(events = eventStore.getEvents()) }
            }
    }

    private suspend fun requireUserId(): String {
        // Firebase can replace an anonymous user with a different account UID during sign-in.
        // Always re-read the active UID so an EventCoordinator retained across Activity
        // recreation never creates an event using the previous guest identity.
        val userId = remoteRepository.requireUserId()
        if (cachedUserId != userId) {
            cachedUserId = userId
            _uiState.update { it.copy(currentUserId = userId) }
        }
        return userId
    }

    private fun Throwable.readableMessage(prefix: String): String =
        "$prefix: ${localizedMessage?.takeIf(String::isNotBlank) ?: "unknown error"}"

}
