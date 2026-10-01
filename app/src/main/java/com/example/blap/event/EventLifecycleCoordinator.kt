package com.example.blap.event

import com.example.blap.chat.IdentityStore
import java.security.KeyPair
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns event lifecycle commands and synchronization with local and remote storage. */
internal class EventLifecycleCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val adminKeyStore: EventAdminKeyStore,
    private val identityStore: IdentityStore,
    private val meshGateway: EventMeshGateway,
    private val scope: CoroutineScope,
    private val currentState: () -> EventUiState,
    private val updateState: (((EventUiState) -> EventUiState) -> Unit),
    private val closeEventObservers: () -> Unit,
    private val clock: () -> Long,
) {
    private var cachedUserId: String? = null
    private var createRequestInFlight = false
    private var eventMutationInFlight = false
    private val tombstoneCleanupInFlight = mutableSetOf<String>()
    private var eventObserver: AutoCloseable? = null
    private var invitationObserver: AutoCloseable? = null

    fun start() {
        scope.launch {
            runCatching {
                val userId = requireUserId()
                updateState { it.copy(currentUserId = userId) }
                startEventObserver()
                startInvitationObserver()
            }.onFailure { failure ->
                updateState { state ->
                    state.copy(error = failure.readableEventMessage("Could not connect to events"))
                }
            }
        }
    }

    fun accountChanged() {
        closeRemoteObservers()
        closeEventObservers()
        cachedUserId = null
        scope.launch {
            runCatching {
                val userId = requireUserId()
                updateState { it.copy(currentUserId = userId) }
                startEventObserver()
                startInvitationObserver()
                refreshEvents()
            }.onFailure { failure ->
                updateState { it.copy(error = failure.readableEventMessage("Could not refresh events")) }
            }
        }
    }

    fun refreshEvents() {
        updateState { it.copy(events = eventStore.getEvents(), loading = true, error = null) }
        scope.launch {
            runCatching {
                requireUserId()
                startEventObserver()
                remoteRepository.listEvents()
            }.onSuccess { remoteEvents ->
                remoteEvents.forEach(eventStore::saveEvent)
                val invitations = runCatching { remoteRepository.listInvitations() }.getOrDefault(emptyList())
                updateState {
                    it.copy(
                        events = eventStore.getEvents(),
                        invitations = invitations.filter { invitation ->
                            invitation.status == EventInvitationStatus.PENDING
                        },
                        loading = false,
                    )
                }
            }.onFailure { failure ->
                updateState {
                    it.copy(
                        loading = false,
                        notice = if (it.events.isEmpty()) null else "Showing cached events.",
                        error = if (it.events.isEmpty()) {
                            failure.readableEventMessage("Events could not be loaded")
                        } else null,
                    )
                }
            }
        }
    }

    fun beginEdit() {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventLifecyclePolicy.editPermissionError(event, membership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        updateState { it.copy(page = EventPage.EDIT, error = null) }
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
        val request = EventCreateRequest(
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
        EventLifecyclePolicy.createError(request, clock(), remoteRepository.hasSignedInAccount())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        createRequestInFlight = true
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching {
                    val userId = requireUserId()
                    val keys = adminKeyStore.getOrCreate(userId)
                    val creation = EventLifecyclePolicy.createModels(
                        request = request,
                        eventId = UUID.randomUUID().toString(),
                        userId = userId,
                        displayName = identityStore.getDisplayName(),
                        keys = keys,
                        createdAt = clock(),
                        joinedAt = clock(),
                        privateMeshSecret = if (visibility == EventVisibility.PRIVATE) {
                            EventSecrets.newMeshSecret()
                        } else "",
                    )
                    remoteRepository.createEvent(creation.event, creation.membership)
                    eventStore.saveEvent(creation.event)
                    eventStore.saveMembership(creation.membership)
                    creation
                }.onSuccess { creation ->
                    updateState {
                        it.copy(
                            page = EventPage.DETAIL,
                            events = eventStore.getEvents(),
                            selectedEventId = creation.event.id,
                            membership = creation.membership,
                            loading = false,
                            notice = "Event created.",
                        )
                    }
                }.onFailure { failure ->
                    updateState {
                        it.copy(loading = false, error = failure.readableEventMessage("Event could not be created"))
                    }
                }
            } finally {
                createRequestInFlight = false
            }
        }
    }

    fun updateSelectedEvent(request: EventCreateRequest) {
        if (eventMutationInFlight) return
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventLifecyclePolicy.editPermissionError(event, membership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        EventLifecyclePolicy.updateError(request, clock())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            updateState { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val updated = EventLifecyclePolicy.updatedEvent(event, request, clock())
        val unsigned = EventMutation(updated, membership.userId)
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, keys.private))
        eventMutationInFlight = true
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching { remoteRepository.updateEvent(updated) }
                    .onSuccess {
                        eventStore.saveEvent(updated)
                        meshGateway.sendEventMutation(mutation)
                        updateState {
                            it.copy(
                                page = EventPage.DETAIL,
                                events = eventStore.getEvents(),
                                loading = false,
                                notice = "Event updated for all attendees.",
                            )
                        }
                    }
                    .onFailure { failure ->
                        updateState {
                            it.copy(loading = false, error = failure.readableEventMessage("Event could not be updated"))
                        }
                    }
            } finally {
                eventMutationInFlight = false
            }
        }
    }

    fun deleteSelectedEvent() {
        if (eventMutationInFlight) return
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventLifecyclePolicy.deletePermissionError(event, membership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            updateState { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val deleted = EventLifecyclePolicy.deletedEvent(event, clock())
        val unsigned = EventMutation(deleted, membership.userId)
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, keys.private))
        eventMutationInFlight = true
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching { remoteRepository.deleteEvent(event.id) }
                    .onSuccess {
                        closeEventObservers()
                        meshGateway.sendEventMutation(mutation)
                        meshGateway.setActiveEvent(null)
                        eventStore.purgeEvent(event.id)
                        updateState {
                            EventUiState(
                                events = eventStore.getEvents(),
                                notice = "Event and all associated data were permanently deleted.",
                            )
                        }
                    }
                    .onFailure { failure ->
                        updateState {
                            it.copy(loading = false, error = failure.readableEventMessage("Event could not be deleted"))
                        }
                    }
            } finally {
                eventMutationInFlight = false
            }
        }
    }

    fun openEvent(eventId: String) {
        val event = eventStore.getEvent(eventId) ?: return
        closeEventObservers()
        updateState {
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
            updateState { it.copy(membership = eventStore.getMembership(event.id, userId)) }
            refreshMembers(event.id)
            refreshAnnouncements(event.id)
            if (event.visibility == EventVisibility.PRIVATE && event.isAdmin(userId)) {
                refreshEventInvitations(event.id)
            }
        }
    }

    fun deleteSelectedEventData() {
        val event = currentState().selectedEvent ?: return
        scope.launch {
            val userId = cachedUserId ?: runCatching { requireUserId() }.getOrNull() ?: return@launch
            eventStore.deleteLocalEventData(event.id, userId)
            meshGateway.setActiveEvent(null)
            updateState {
                EventUiState(events = eventStore.getEvents(), notice = "Local event data deleted.")
            }
        }
    }

    fun showAnnouncements() {
        val event = currentState().selectedEvent ?: return
        updateState {
            it.copy(
                page = EventPage.ANNOUNCEMENTS,
                announcements = eventStore.getAnnouncements(event.id),
                error = null,
            )
        }
        refreshAnnouncements(event.id)
    }

    fun publishAnnouncement(text: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        val cleanText = text.trim().take(1_000)
        EventLifecyclePolicy.announcementError(event, membership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        if (cleanText.isBlank()) return
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            updateState { it.copy(error = "This device does not have the admin signing key.") }
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
        meshGateway.sendEventAnnouncement(announcement)
        updateState { it.copy(announcements = eventStore.getAnnouncements(event.id)) }
        scope.launch {
            runCatching { remoteRepository.saveAnnouncement(announcement) }
                .onSuccess {
                    eventStore.markAnnouncementSynced(announcement.id)
                    updateState { it.copy(announcements = eventStore.getAnnouncements(event.id)) }
                }
                .onFailure {
                    updateState {
                        it.copy(notice = "Announcement sent on-site and will upload when retried online.")
                    }
                }
        }
    }

    fun close() {
        closeRemoteObservers()
    }

    suspend fun requireUserId(): String {
        val userId = remoteRepository.requireUserId()
        if (cachedUserId != userId) {
            cachedUserId = userId
            updateState { it.copy(currentUserId = userId) }
        }
        return userId
    }

    @Synchronized
    private fun startInvitationObserver() {
        if (invitationObserver != null) return
        invitationObserver = remoteRepository.observeInvitations(
            onInvitations = { invitations ->
                updateState { state ->
                    state.copy(
                        invitations = invitations.filter { it.status == EventInvitationStatus.PENDING }
                            .sortedBy(EventInvitation::startsAt),
                    )
                }
            },
            onError = { failure ->
                updateState { it.copy(notice = failure.readableEventMessage("Invitations will refresh when online")) }
            },
        )
    }

    @Synchronized
    private fun startEventObserver() {
        if (eventObserver != null) return
        eventObserver = remoteRepository.observeEvents(
            onEvents = { remoteEvents, authoritative ->
                val reconciliation = EventLifecyclePolicy.reconcileEvents(
                    localEvents = eventStore.getEvents(),
                    remoteEvents = remoteEvents,
                    authoritative = authoritative,
                )
                reconciliation.activeEvents.forEach(eventStore::saveEvent)
                reconciliation.removedEventIds.forEach(eventStore::purgeEvent)
                val state = currentState()
                if (state.activeEventId in reconciliation.removedEventIds) meshGateway.setActiveEvent(null)
                if (state.selectedEventId in reconciliation.removedEventIds) closeEventObservers()
                updateState { current ->
                    if (current.selectedEventId in reconciliation.removedEventIds) {
                        EventUiState(
                            events = eventStore.getEvents(),
                            notice = "The event was deleted by its primary admin.",
                        )
                    } else {
                        current.copy(events = eventStore.getEvents(), loading = false)
                    }
                }
                if (authoritative) reconciliation.tombstones.forEach(::finishLegacyDeletion)
            },
            onError = { failure ->
                updateState { state ->
                    if (state.events.isEmpty()) {
                        state.copy(error = failure.readableEventMessage("Events could not be updated"))
                    } else {
                        state.copy(notice = "Event updates will resume when you are online.")
                    }
                }
            },
        )
    }

    private fun closeRemoteObservers() {
        eventObserver?.close()
        invitationObserver?.close()
        eventObserver = null
        invitationObserver = null
    }

    private fun finishLegacyDeletion(event: CommunityEvent) {
        if (!tombstoneCleanupInFlight.add(event.id)) return
        scope.launch {
            try {
                val userId = runCatching { requireUserId() }.getOrNull()
                if (userId == event.createdBy) {
                    runCatching { remoteRepository.deleteEvent(event.id) }
                        .onFailure { failure ->
                            updateState {
                                it.copy(error = failure.readableEventMessage("An older deleted event could not be cleaned up"))
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
                    updateState { state ->
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
                    updateState { state ->
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
                    updateState { state ->
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
                updateState { it.copy(events = eventStore.getEvents()) }
            }
    }

}

internal data class EventCreation(
    val event: CommunityEvent,
    val membership: EventMembership,
)

internal data class EventReconciliation(
    val activeEvents: List<CommunityEvent>,
    val removedEventIds: Set<String>,
    val tombstones: List<CommunityEvent>,
)

/** Pure validation and construction rules for event lifecycle operations. */
internal object EventLifecyclePolicy {
    fun createError(request: EventCreateRequest, now: Long, hasSignedInAccount: Boolean): String? = when {
        request.title.trim().take(80).isBlank() -> "Enter an event title."
        request.endsAt <= request.startsAt || request.endsAt <= now ->
            "Choose an end time after the start time."
        !hasSignedInAccount -> "Sign in with Email or Google to create an event."
        else -> null
    }

    fun editPermissionError(event: CommunityEvent, membership: EventMembership): String? =
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || event.isDeleted) {
            "Only an active event admin can edit this event."
        } else null

    fun updateError(request: EventCreateRequest, now: Long): String? = when {
        request.title.trim().take(80).isBlank() || request.venueName.isBlank() ->
            "Add an event title and location."
        request.endsAt <= request.startsAt || request.endsAt <= now ->
            "Choose an end time after the start time."
        else -> null
    }

    fun deletePermissionError(event: CommunityEvent, membership: EventMembership): String? =
        if (membership.role != EventRole.PRIMARY_ADMIN || event.createdBy != membership.userId) {
            "Only the primary admin can delete this event."
        } else null

    fun announcementError(event: CommunityEvent, membership: EventMembership): String? = when {
        event.isDeleted -> "Deleted events are read-only."
        !membership.isAdmin || !event.isAdmin(membership.userId) ->
            "Only event admins can post announcements."
        else -> null
    }

    fun createModels(
        request: EventCreateRequest,
        eventId: String,
        userId: String,
        displayName: String,
        keys: KeyPair,
        createdAt: Long,
        joinedAt: Long,
        privateMeshSecret: String,
    ): EventCreation {
        val publicEvent = request.visibility == EventVisibility.PUBLIC
        val venueCheckInPayload = if (publicEvent) {
            EventCheckInCodec.create(eventId, userId, keys.private)
        } else ""
        val event = CommunityEvent(
            id = eventId,
            title = request.title.trim().take(80),
            description = request.description.trim().take(1_000),
            venueName = request.venueName.trim().take(200),
            latitude = request.latitude,
            longitude = request.longitude,
            radiusMetres = request.radiusMetres,
            startsAt = request.startsAt,
            endsAt = request.endsAt,
            createdBy = userId,
            adminIds = setOf(userId),
            memberIds = setOf(userId),
            adminPublicKeys = mapOf(userId to EventCheckInCodec.encodePublicKey(keys.public)),
            visibility = request.visibility,
            requiresSignIn = publicEvent && request.requiresSignIn,
            privateMeshSecret = if (request.visibility == EventVisibility.PRIVATE) privateMeshSecret else "",
            venueCheckInPayload = venueCheckInPayload,
            createdAt = createdAt,
        )
        return EventCreation(
            event = event,
            membership = EventMembership(
                eventId = event.id,
                userId = userId,
                displayName = displayName,
                role = EventRole.PRIMARY_ADMIN,
                joinedAt = joinedAt,
            ),
        )
    }

    fun updatedEvent(event: CommunityEvent, request: EventCreateRequest, now: Long): CommunityEvent = event.copy(
        title = request.title.trim().take(80),
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
        updatedAt = maxOf(now, event.updatedAt + 1),
    )

    fun deletedEvent(event: CommunityEvent, now: Long): CommunityEvent = event.copy(
        deletedAt = event.deletedAt ?: now,
        updatedAt = maxOf(now, event.updatedAt + 1),
    )

    fun reconcileEvents(
        localEvents: List<CommunityEvent>,
        remoteEvents: List<CommunityEvent>,
        authoritative: Boolean,
    ): EventReconciliation {
        val tombstones = remoteEvents.filter(CommunityEvent::isDeleted)
        val activeEvents = remoteEvents.filterNot(CommunityEvent::isDeleted)
        val activeRemoteIds = activeEvents.mapTo(mutableSetOf(), CommunityEvent::id)
        val removedEventIds = if (authoritative) {
            localEvents.map(CommunityEvent::id).filterNot(activeRemoteIds::contains).toSet()
        } else {
            tombstones.mapTo(mutableSetOf(), CommunityEvent::id)
        }
        return EventReconciliation(activeEvents, removedEventIds, tombstones)
    }
}
