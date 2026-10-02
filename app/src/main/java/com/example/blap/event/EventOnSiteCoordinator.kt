package com.example.blap.event

import com.example.blap.chat.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinates venue admission and traffic carried by the event-specific Nearby mesh. */
internal class EventOnSiteCoordinator(
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
    private var pendingAccessRequest: EventAccessRequest? = null

    fun clearPendingAccess(eventId: String? = null) {
        if (eventId == null || pendingAccessRequest?.eventId == eventId) pendingAccessRequest = null
    }

    fun enterWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) {
        val event = currentState().selectedEvent ?: return
        val membership = currentState().membership
        when (val decision = EventAccessPolicy.evaluateGps(
            event,
            membership,
            latitude,
            longitude,
            accuracyMetres,
            clock(),
        )) {
            is EventEntryDecision.Allowed -> activate(event, requireNotNull(membership), decision.method)
            is EventEntryDecision.NeedsQr -> updateState {
                if (event.visibility == EventVisibility.PRIVATE) {
                    it.copy(
                        notice = if (membership?.isAdmin == true) {
                            "GPS is not accurate enough. Retry when location accuracy improves."
                        } else {
                            "GPS is not accurate enough. Ask an on-site event admin for access."
                        },
                    )
                } else it.copy(notice = decision.reason)
            }
            is EventEntryDecision.Denied -> updateState { it.copy(error = decision.reason) }
        }
    }

    fun enterWithQr(payload: String) {
        val event = currentState().selectedEvent ?: return
        if (event.visibility == EventVisibility.PRIVATE) {
            updateState { it.copy(error = "Private events do not use QR check-in.") }
            return
        }
        if (event.requiresSignIn && !remoteRepository.hasSignedInAccount()) {
            updateState { it.copy(error = "Sign in with Email or Google to join this protected event.") }
            return
        }
        if (event.venueCheckInPayload.isBlank() || payload != event.venueCheckInPayload) {
            updateState { it.copy(error = "This venue check-in QR is not valid for this event.") }
            return
        }
        val credential = event.adminPublicKeys.entries.firstNotNullOfOrNull { (adminId, encodedKey) ->
            runCatching {
                EventCheckInCodec.verify(
                    payload,
                    event.id,
                    EventCheckInCodec.decodePublicKey(encodedKey),
                )?.takeIf { it.adminId == adminId }
            }.getOrNull()
        }
        if (credential == null) {
            updateState { it.copy(error = "This venue check-in QR has an invalid signature.") }
            return
        }
        val membership = currentState().membership
        if (membership == null || membership.leftAt != null) {
            updateState { it.copy(error = "Join this event before scanning its venue QR.") }
            return
        }
        when (val decision = EventAccessPolicy.evaluateQr(event, membership, credential, clock())) {
            is EventEntryDecision.Allowed -> activate(event, membership, decision.method)
            is EventEntryDecision.NeedsQr -> updateState { it.copy(notice = decision.reason) }
            is EventEntryDecision.Denied -> updateState { it.copy(error = decision.reason) }
        }
    }

    fun createVenueCheckInQr(): String? {
        val event = currentState().selectedEvent ?: return null
        if (event.visibility == EventVisibility.PRIVATE) return null
        val membership = currentState().membership ?: return null
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || !event.isActive(clock())) return null
        if (event.venueCheckInPayload.isNotBlank()) return event.venueCheckInPayload
        if (membership.role != EventRole.PRIMARY_ADMIN || event.createdBy != membership.userId) return null
        val keys = adminKeyStore.get(membership.userId) ?: return null
        val payload = EventCheckInCodec.create(event.id, membership.userId, keys.private)
        val updated = event.copy(
            venueCheckInPayload = payload,
            updatedAt = maxOf(clock(), event.updatedAt + 1),
        )
        val unsigned = EventMutation(updated, membership.userId)
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, keys.private))
        eventStore.saveEvent(updated)
        updateState { it.copy(events = it.events.upsertEvent(updated)) }
        scope.launch {
            runCatching { remoteRepository.updateEvent(updated) }
                .onSuccess { meshGateway.sendEventMutation(mutation) }
                .onFailure { failure ->
                    updateState {
                        it.copy(notice = failure.readableEventMessage("The static QR was saved locally but could not be synced"))
                    }
                }
        }
        return payload
    }

    fun showVenueCheckInQr() {
        val payload = createVenueCheckInQr()
        updateState {
            if (payload == null) it.copy(
                error = "The static check-in QR is available to event admins while the event is active. " +
                    "For older events, the primary admin must create it first.",
            ) else it.copy(checkInQrPayload = payload, error = null)
        }
    }

    fun hideVenueCheckInQr() {
        updateState { it.copy(checkInQrPayload = null) }
    }

    fun sendMessage(text: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        val cleanText = text.trim().take(1_000)
        if (cleanText.isBlank()) return
        if (state.activeEventId != event.id || !event.isActive(clock()) || !membership.canParticipate) {
            updateState { it.copy(error = "On-site chat is not currently active.") }
            return
        }
        val message = EventChatMessage(
            eventId = event.id,
            senderId = membership.userId,
            senderName = membership.displayName,
            text = cleanText,
            createdAt = clock(),
        )
        eventStore.saveChatMessage(message)
        meshGateway.sendEventChatMessage(message)
        updateState { it.copy(chatMessages = eventStore.getChatMessages(event.id)) }
    }

    fun showSavedHistory() {
        val event = currentState().selectedEvent ?: return
        updateState {
            it.copy(
                page = EventPage.ON_SITE_CHAT,
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
    }

    fun requestAdminAccess() {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventOnSitePolicy.manualAccessRequestError(
            event = event,
            membership = membership,
            activeEventId = state.activeEventId,
            now = clock(),
        )?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val request = EventAccessRequest(
            eventId = event.id,
            userId = membership.userId,
            peerId = identityStore.getPeerId(),
            displayName = membership.displayName,
            requestedAt = clock(),
        )
        pendingAccessRequest = request
        meshGateway.setActiveEvent(
            eventId = event.id,
            meshSecret = event.privateMeshSecret,
            userId = membership.userId,
            accessGranted = false,
        )
        meshGateway.sendEventAccessRequest(request)
        updateState {
            it.copy(waitingForAdminAccess = true, notice = "Waiting for a nearby event admin to approve access.")
        }
    }

    fun approveAccess(requestId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        val request = state.accessRequests.firstOrNull { it.id == requestId } ?: return
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || request.userId !in event.memberIds) {
            updateState { it.copy(error = "This access request cannot be approved.") }
            return
        }
        val target = state.members.firstOrNull { it.userId == request.userId }
        if (target != null && !target.canParticipate) {
            updateState { it.copy(error = "This member has left or been removed.") }
            return
        }
        val keys = adminKeyStore.get(membership.userId)
        if (keys == null) {
            updateState { it.copy(error = "This device does not have the admin signing key.") }
            return
        }
        val issuedAt = clock()
        val unsigned = EventAccessGrant(
            requestId = request.id,
            eventId = event.id,
            userId = request.userId,
            peerId = request.peerId,
            adminId = membership.userId,
            issuedAt = issuedAt,
            expiresAt = issuedAt + EventOnSitePolicy.ACCESS_GRANT_LIFETIME_MILLIS,
        )
        val grant = unsigned.copy(signature = EventAccessGrantSigner.sign(unsigned, keys.private))
        meshGateway.sendEventAccessGrant(grant)
        updateState {
            it.copy(
                accessRequests = it.accessRequests.filterNot { access -> access.id == requestId },
                notice = "On-site access approved for ${request.displayName}.",
            )
        }
    }

    fun onPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) {
        val state = currentState()
        pendingAccessRequest?.takeIf { it.eventId == eventId }?.let(meshGateway::sendEventAccessRequest)
        val event = state.events.firstOrNull { it.id == eventId } ?: return
        if (!EventOnSitePolicy.canSynchronizePeer(event, state.activeEventId, userId, accessGranted)) return
        meshGateway.synchronizeEventHistory(peerId, eventStore.getRecentChatMessages(eventId, 50))
        meshGateway.synchronizeEventAnnouncements(peerId, eventStore.getAnnouncements(eventId, 100))
    }

    fun onAccessRequestReceived(request: EventAccessRequest) {
        val state = currentState()
        val event = state.events.firstOrNull { it.id == request.eventId } ?: return
        val membership = state.membership ?: return
        if (!EventOnSitePolicy.canAcceptAccessRequest(event, membership, state.activeEventId, request, clock())) return
        updateState { current ->
            current.copy(
                accessRequests = (current.accessRequests.filterNot { it.id == request.id } + request)
                    .sortedBy(EventAccessRequest::requestedAt),
            )
        }
    }

    fun onAccessGrantReceived(grant: EventAccessGrant) {
        val request = pendingAccessRequest ?: return
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        if (!EventOnSitePolicy.isValidAccessGrant(
                request = request,
                event = event,
                membership = membership,
                localPeerId = identityStore.getPeerId(),
                grant = grant,
                now = clock(),
            )
        ) return
        pendingAccessRequest = null
        updateState { it.copy(waitingForAdminAccess = false) }
        activate(event, membership, EventAccessMethod.ADMIN_APPROVAL)
    }

    fun onChatMessageReceived(message: EventChatMessage) {
        val state = currentState()
        val event = state.events.firstOrNull { it.id == message.eventId } ?: return
        val membership = state.membership ?: return
        if (!EventOnSitePolicy.canAcceptChatMessage(
                event = event,
                membership = membership,
                knownMembers = state.members,
                activeEventId = state.activeEventId,
                message = message,
                now = clock(),
            )
        ) return
        if (eventStore.saveChatMessage(message)) {
            updateState { it.copy(chatMessages = eventStore.getChatMessages(event.id)) }
        }
    }

    fun onAnnouncementReceived(announcement: EventAnnouncement) {
        val event = currentState().events.firstOrNull { it.id == announcement.eventId }
            ?: eventStore.getEvent(announcement.eventId)
            ?: return
        if (!EventOnSitePolicy.isValidAnnouncement(event, announcement)) return
        if (eventStore.saveAnnouncement(announcement)) {
            updateState { state ->
                if (state.selectedEventId == event.id) {
                    state.copy(announcements = eventStore.getAnnouncements(event.id))
                } else state
            }
        }
    }

    fun onMutationReceived(mutation: EventMutation) {
        val existing = eventStore.getEvent(mutation.event.id) ?: return
        val updated = EventOnSitePolicy.verifiedMutationEvent(existing, mutation) ?: return
        if (updated.isDeleted) {
            clearPendingAccess(updated.id)
            closeEventObservers()
            eventStore.purgeEvent(updated.id)
            meshGateway.setActiveEvent(null)
            updateState {
                EventUiState(
                    events = it.events.withoutEvent(updated.id),
                    invitations = it.invitations,
                    currentUserId = it.currentUserId,
                    notice = "The event and its local data were deleted by the primary admin.",
                )
            }
            return
        }
        eventStore.saveEvent(updated)
        updateState { state ->
            state.copy(events = state.events.upsertEvent(updated), notice = "Event details were updated.")
        }
    }

    private fun activate(
        event: CommunityEvent,
        membership: EventMembership,
        method: EventAccessMethod,
    ) {
        pendingAccessRequest = null
        val checkedIn = membership.copy(accessMethod = method, checkedInAt = clock())
        eventStore.saveMembership(checkedIn)
        meshGateway.setActiveEvent(
            eventId = event.id,
            meshSecret = event.privateMeshSecret,
            userId = checkedIn.userId,
            accessGranted = true,
        )
        updateState {
            it.copy(
                page = EventPage.ON_SITE_CHAT,
                membership = checkedIn,
                activeEventId = event.id,
                waitingForAdminAccess = false,
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
    }

}

/** Pure validation boundary for untrusted packets received from the event mesh. */
internal object EventOnSitePolicy {
    const val ACCESS_GRANT_LIFETIME_MILLIS = 10 * 60 * 1_000L
    private const val ACCESS_REQUEST_MAX_AGE_MILLIS = 10 * 60 * 1_000L

    fun manualAccessRequestError(
        event: CommunityEvent,
        membership: EventMembership,
        activeEventId: String?,
        now: Long,
    ): String? = when {
        event.visibility != EventVisibility.PRIVATE || !event.isActive(now) || !membership.canParticipate ->
            "Manual access is only available to accepted members during a private event."
        membership.isAdmin -> "Event admins do not need approval. Use Enter on-site chat."
        activeEventId == event.id -> "You already have on-site access."
        else -> null
    }

    fun canSynchronizePeer(
        event: CommunityEvent,
        activeEventId: String?,
        peerUserId: String,
        accessGranted: Boolean,
    ): Boolean = activeEventId == event.id &&
        accessGranted &&
        (event.visibility != EventVisibility.PRIVATE || peerUserId in event.memberIds)

    fun canAcceptAccessRequest(
        event: CommunityEvent,
        localMembership: EventMembership,
        activeEventId: String?,
        request: EventAccessRequest,
        now: Long,
    ): Boolean = activeEventId == event.id &&
        event.visibility == EventVisibility.PRIVATE &&
        localMembership.isAdmin &&
        event.isAdmin(localMembership.userId) &&
        request.eventId == event.id &&
        request.userId in event.memberIds &&
        request.requestedAt in
        (now - ACCESS_REQUEST_MAX_AGE_MILLIS)..(now + EventAccessPolicy.MAX_CLOCK_SKEW_MILLIS)

    fun isValidAccessGrant(
        request: EventAccessRequest,
        event: CommunityEvent,
        membership: EventMembership,
        localPeerId: String,
        grant: EventAccessGrant,
        now: Long,
    ): Boolean {
        if (grant.requestId != request.id || grant.eventId != event.id || grant.userId != membership.userId ||
            grant.peerId != localPeerId || grant.adminId !in event.adminIds ||
            now !in grant.issuedAt..grant.expiresAt ||
            grant.expiresAt - grant.issuedAt > ACCESS_GRANT_LIFETIME_MILLIS
        ) return false
        val publicKey = event.adminPublicKeys[grant.adminId]
            ?.let { runCatching { EventCheckInCodec.decodePublicKey(it) }.getOrNull() }
            ?: return false
        return EventAccessGrantSigner.verify(grant, publicKey)
    }

    fun canAcceptChatMessage(
        event: CommunityEvent,
        membership: EventMembership,
        knownMembers: List<EventMembership>,
        activeEventId: String?,
        message: EventChatMessage,
        now: Long,
    ): Boolean = message.eventId == event.id &&
        activeEventId == event.id &&
        event.isActive(now) &&
        membership.canParticipate &&
        (knownMembers.isEmpty() || knownMembers.any { it.userId == message.senderId && it.canParticipate })

    fun isValidAnnouncement(event: CommunityEvent, announcement: EventAnnouncement): Boolean {
        if (announcement.eventId != event.id || announcement.adminId !in event.adminIds) return false
        val publicKey = event.adminPublicKeys[announcement.adminId]
            ?.let { runCatching { EventCheckInCodec.decodePublicKey(it) }.getOrNull() }
            ?: return false
        return EventAnnouncementSigner.verify(announcement, publicKey)
    }

    fun verifiedMutationEvent(existing: CommunityEvent, mutation: EventMutation): CommunityEvent? {
        if (mutation.event.id != existing.id) return null
        val normalized = mutation.copy(
            event = mutation.event.copy(
                memberIds = existing.memberIds,
                visibility = existing.visibility,
                privateMeshSecret = existing.privateMeshSecret,
            ),
        )
        if (normalized.event.createdBy != existing.createdBy) return null
        if (normalized.adminId !in existing.adminIds || normalized.event.updatedAt <= existing.updatedAt) return null
        if (normalized.event.deletedAt != null && normalized.adminId != existing.createdBy) return null
        val publicKey = existing.adminPublicKeys[normalized.adminId]
            ?.let { runCatching { EventCheckInCodec.decodePublicKey(it) }.getOrNull() }
            ?: return null
        return normalized.event.takeIf { EventMutationSigner.verify(normalized, publicKey) }
    }
}
