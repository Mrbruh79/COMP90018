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
    private var announcementObserver: AutoCloseable? = null
    private var observedAnnouncementEventId: String? = null

    fun start() {
        scope.launch {
            runCatching {
                val userId = requireUserId()
                val cachedEvents = cachedEventPackages(userId)
                updateState {
                    it.copy(
                        events = cachedEvents,
                        showingOfflineEvents = cachedEvents.isNotEmpty(),
                        currentUserId = userId,
                        loading = true,
                    )
                }
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
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val userId = requireUserId()
                startEventObserver()
                userId to remoteRepository.listEvents()
            }.onSuccess { (userId, remoteEvents) ->
                val reconciliation = EventLifecyclePolicy.reconcileEvents(
                    localEvents = cachedEventPackages(userId),
                    remoteEvents = remoteEvents,
                    authoritative = true,
                    currentUserId = userId,
                )
                val packageIds = reconciliation.cacheableEvents.mapTo(mutableSetOf(), CommunityEvent::id)
                eventStore.getEvents().map(CommunityEvent::id).filterNot(packageIds::contains)
                    .forEach(eventStore::purgeEvent)
                reconciliation.removedEventIds.forEach(eventStore::purgeEvent)
                reconciliation.cacheableEvents.forEach(eventStore::saveEvent)
                val invitations = runCatching { remoteRepository.listInvitations() }.getOrDefault(emptyList())
                updateState {
                    it.copy(
                        events = mergeJoinedEvents(it, reconciliation.visibleEvents),
                        invitations = invitations.filter { invitation ->
                            invitation.status == EventInvitationStatus.PENDING
                        },
                        showingOfflineEvents = reconciliation.showingOfflineEvents,
                        loading = false,
                    )
                }
            }.onFailure { failure ->
                updateState {
                    val cachedEvents = cachedEventPackages(it.currentUserId)
                    it.copy(
                        events = mergeJoinedEvents(it, cachedEvents),
                        showingOfflineEvents = cachedEvents.isNotEmpty(),
                        loading = false,
                        notice = if (cachedEvents.isNotEmpty()) {
                            "Event updates will resume when you are online."
                        } else null,
                        error = if (cachedEvents.isEmpty()) {
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
                            events = it.events.upsertEvent(creation.event),
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
                                events = it.events.upsertEvent(updated),
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
                        closeAnnouncementObserver()
                        closeEventObservers()
                        meshGateway.sendEventMutation(mutation)
                        meshGateway.setActiveEvent(null)
                        eventStore.purgeEvent(event.id)
                        updateState {
                            it.returnToEventList(
                                events = it.events.withoutEvent(event.id),
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
        val event = currentState().events.firstOrNull { it.id == eventId }
            ?: eventStore.getEvent(eventId)
            ?: return
        closeAnnouncementObserver()
        closeEventObservers()
        val cachedMembership = eventStore.getMembership(event.id, currentState().currentUserId)
        updateState {
            it.copy(
                page = EventPage.DETAIL,
                selectedEventId = event.id,
                membership = cachedMembership,
                activeEventId = event.id.takeIf { EventCheckInState.isCheckedIn(event, cachedMembership, clock()) },
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
            val membership = eventStore.getMembership(event.id, userId)
            updateState {
                if (it.selectedEventId != event.id) it else it.copy(membership = membership,
                    activeEventId = event.id.takeIf { EventCheckInState.isCheckedIn(event, membership, clock()) })
            }
            if (currentState().selectedEventId != event.id) return@launch
            refreshMembers(event.id)
            refreshAnnouncements(event.id)
            if (event.visibility == EventVisibility.PRIVATE && event.isAdmin(userId)) {
                refreshEventInvitations(event.id)
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
        refreshAnnouncementMesh()
        startAnnouncementObserver(event.id)
        refreshAnnouncements(event.id)
    }

    private fun refreshAnnouncementMesh() {
        val selection = EventMeshSelection.select(currentState(), eventsVisible = true, now = clock()) ?: return
        meshGateway.refreshEventMesh(selection.eventId)
    }

    fun closeAnnouncementObserver() {
        announcementObserver?.close()
        announcementObserver = null
        observedAnnouncementEventId = null
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
        refreshAnnouncementMesh()
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
                        it.copy(notice = "Announcement saved locally. It can sync through the active event mesh. " +
                            "Cloud upload will retry when announcements are refreshed online.")
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
                val userId = cachedUserId ?: return@observeEvents
                val storedEvents = eventStore.getEvents()
                val localEvents = cachedEventPackages(userId)
                val reconciliation = EventLifecyclePolicy.reconcileEvents(
                    localEvents = localEvents,
                    remoteEvents = remoteEvents,
                    authoritative = authoritative,
                    currentUserId = userId,
                )
                val locallyRemovedEventIds = reconciliation.removedEventIds
                if (authoritative) {
                    val localPackageIds = localEvents.mapTo(mutableSetOf(), CommunityEvent::id)
                    storedEvents.map(CommunityEvent::id).filterNot(localPackageIds::contains)
                        .forEach(eventStore::purgeEvent)
                }
                locallyRemovedEventIds.forEach(eventStore::purgeEvent)
                reconciliation.cacheableEvents.forEach(eventStore::saveEvent)
                val state = currentState()
                if (state.activeEventId in locallyRemovedEventIds) meshGateway.setActiveEvent(null)
                val selectedWasJoined = state.selectedEvent?.let { userId in it.memberIds } == true
                val selectedEventUnavailable = selectedWasJoined && state.selectedEventId != null &&
                    reconciliation.visibleEvents.none { it.id == state.selectedEventId }
                if (selectedEventUnavailable) {
                    closeAnnouncementObserver()
                    closeEventObservers()
                }
                updateState { current ->
                    if (selectedEventUnavailable) {
                        current.returnToEventList(
                            events = mergeJoinedEvents(current, reconciliation.visibleEvents),
                            showingOfflineEvents = reconciliation.showingOfflineEvents,
                            notice = if (current.selectedEventId in locallyRemovedEventIds) {
                                "You no longer have access to this event. All local event data was deleted."
                            } else if (authoritative) {
                                "This event is no longer available."
                            } else {
                                "Join this event online before using it offline."
                            },
                        )
                    } else {
                        current.copy(
                            events = mergeJoinedEvents(current, reconciliation.visibleEvents),
                            showingOfflineEvents = reconciliation.showingOfflineEvents,
                            loading = false,
                        )
                    }
                }
                val selectedEvent = remoteEvents.firstOrNull { it.id == currentState().selectedEventId }
                if (
                    authoritative &&
                    selectedEvent?.visibility == EventVisibility.PRIVATE &&
                    selectedEvent.isAdmin(userId)
                ) {
                    refreshEventInvitations(selectedEvent.id)
                }
                if (authoritative) reconciliation.tombstones.forEach(::finishLegacyDeletion)
            },
            onError = { failure ->
                updateState { state ->
                    val cachedEvents = cachedEventPackages(state.currentUserId)
                    val selectedWasJoined = state.selectedEvent?.let { state.currentUserId in it.memberIds } == true
                    if (selectedWasJoined && state.selectedEventId != null &&
                        cachedEvents.none { it.id == state.selectedEventId }
                    ) {
                        closeAnnouncementObserver()
                        closeEventObservers()
                        state.returnToEventList(
                            events = mergeJoinedEvents(state, cachedEvents),
                            showingOfflineEvents = cachedEvents.isNotEmpty(),
                            notice = "Join this event online before using it offline.",
                        )
                    } else if (cachedEvents.isEmpty()) {
                        state.copy(
                            events = mergeJoinedEvents(state, emptyList()),
                            loading = false,
                            showingOfflineEvents = false,
                            error = failure.readableEventMessage("Events could not be updated"),
                        )
                    } else {
                        state.copy(
                            events = mergeJoinedEvents(state, cachedEvents),
                            loading = false,
                            showingOfflineEvents = true,
                            notice = "Event updates will resume when you are online.",
                        )
                    }
                }
            },
        )
    }

    private fun closeRemoteObservers() {
        eventObserver?.close()
        invitationObserver?.close()
        closeAnnouncementObserver()
        eventObserver = null
        invitationObserver = null
    }

    private fun startAnnouncementObserver(eventId: String) {
        if (observedAnnouncementEventId == eventId && announcementObserver != null) return
        closeAnnouncementObserver()
        observedAnnouncementEventId = eventId
        announcementObserver = remoteRepository.observeAnnouncements(
            eventId = eventId,
            onAnnouncements = { announcements ->
                announcements.forEach(::saveRemoteAnnouncement)
                updateState { state ->
                    if (state.selectedEventId == eventId && state.page == EventPage.ANNOUNCEMENTS) {
                        state.copy(announcements = eventStore.getAnnouncements(eventId))
                    } else state
                }
            },
            onError = { failure ->
                updateState { state ->
                    if (state.selectedEventId == eventId && state.page == EventPage.ANNOUNCEMENTS) {
                        state.copy(
                            notice = failure.readableEventMessage(
                                "Announcements will update when the connection returns",
                            ),
                        )
                    } else state
                }
            },
        )
        if (announcementObserver == null) observedAnnouncementEventId = null
    }

    private fun cachedEventPackages(userId: String): List<CommunityEvent> = eventStore.getEvents().filter { event ->
        !event.isDeleted && eventStore.getMembership(event.id, userId)?.canParticipate == true
    }

    private fun mergeJoinedEvents(
        state: EventUiState,
        joinedEvents: List<CommunityEvent>,
    ): List<CommunityEvent> {
        val discoveryIds = state.discoveryEventIds.toSet()
        val discovered = state.events.filter { it.id in discoveryIds }
        val selected = state.selectedEvent?.takeIf { selectedEvent ->
            joinedEvents.none { it.id == selectedEvent.id } && discovered.none { it.id == selectedEvent.id }
        }
        return (joinedEvents + discovered + listOfNotNull(selected))
            .distinctBy(CommunityEvent::id)
            .sortedBy(CommunityEvent::startsAt)
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
                    remote.forEach(::saveRemoteAnnouncement)
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

    private fun saveRemoteAnnouncement(announcement: EventAnnouncement) {
        val added = eventStore.saveAnnouncement(announcement)
        val selection = EventMeshSelection.select(currentState(), eventsVisible = true, now = clock())
        if (added && selection?.eventId == announcement.eventId) {
            // A connected attendee can carry a new cloud announcement to offline event peers.
            meshGateway.sendEventAnnouncement(announcement)
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
                    if (localUserId != null && cachedMembership != null && localMembership?.canParticipate != true) {
                        val event = eventStore.getEvent(eventId)
                        if (currentState().activeEventId == eventId) meshGateway.setActiveEvent(null)
                        closeEventObservers()
                        eventStore.purgeEvent(eventId)
                        val visibleAfterDeparture = event?.let {
                            EventMembershipPolicy.eventVisibleAfterDeparture(it, localUserId)
                        }
                        updateState { state ->
                            val visibleEvents = state.events.withoutEvent(eventId).let { events ->
                                visibleAfterDeparture?.let(events::upsertEvent) ?: events
                            }
                            if (state.selectedEventId == eventId) {
                                state.returnToEventList(
                                    events = visibleEvents,
                                    notice = "You no longer have access to this event. All local event data was deleted.",
                                )
                            } else {
                                state.copy(events = visibleEvents)
                            }
                        }
                    } else if (localMembership != null) {
                        val mergedMembership = EventCheckInState.mergeMembership(localMembership, cachedMembership)
                        eventStore.saveMembership(mergedMembership)
                        if (localMembership.isAdmin) registerLocalAdminKey(eventId, localMembership.userId)
                        updateState { state ->
                            if (state.selectedEventId == eventId) {
                                val event = state.selectedEvent
                                state.copy(members = members, membership = mergedMembership,
                                    activeEventId = event?.id?.takeIf { EventCheckInState.isCheckedIn(event, mergedMembership, clock()) })
                            } else state
                        }
                    } else {
                        updateState { state ->
                            if (state.selectedEventId == eventId) {
                                state.copy(members = members, membership = null)
                            } else state
                        }
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
                val updated = event.copy(adminPublicKeys = event.adminPublicKeys + (userId to encoded))
                eventStore.saveEvent(updated)
                updateState { it.copy(events = it.events.upsertEvent(updated)) }
            }
    }

}

internal data class EventCreation(
    val event: CommunityEvent,
    val membership: EventMembership,
)

internal data class EventReconciliation(
    val visibleEvents: List<CommunityEvent>,
    val cacheableEvents: List<CommunityEvent>,
    val removedEventIds: Set<String>,
    val tombstones: List<CommunityEvent>,
    val showingOfflineEvents: Boolean,
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
        val venueCheckInPayload = EventCheckInCodec.create(eventId, userId, keys.private)
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
        currentUserId: String,
    ): EventReconciliation {
        val tombstones = remoteEvents.filter(CommunityEvent::isDeleted)
        val activeEvents = remoteEvents.filterNot(CommunityEvent::isDeleted)
        val cacheableEvents = activeEvents.filter { currentUserId in it.memberIds }
        val visibleRemoteEvents = cacheableEvents
        val cacheableRemoteIds = cacheableEvents.mapTo(mutableSetOf(), CommunityEvent::id)
        val tombstoneIds = tombstones.mapTo(mutableSetOf(), CommunityEvent::id)
        val removedEventIds = if (authoritative) {
            localEvents.map(CommunityEvent::id).filterNot(cacheableRemoteIds::contains).toSet()
        } else {
            tombstoneIds
        }
        val fallbackEvents = if (authoritative) emptyList() else localEvents.filter { cached ->
            cached.id !in tombstoneIds && visibleRemoteEvents.none { it.id == cached.id }
        }
        val visibleEvents = (visibleRemoteEvents + fallbackEvents)
            .distinctBy(CommunityEvent::id)
            .sortedBy(CommunityEvent::startsAt)
        return EventReconciliation(
            visibleEvents = visibleEvents,
            cacheableEvents = cacheableEvents,
            removedEventIds = removedEventIds,
            tombstones = tombstones,
            showingOfflineEvents = !authoritative && visibleEvents.isNotEmpty(),
        )
    }
}
