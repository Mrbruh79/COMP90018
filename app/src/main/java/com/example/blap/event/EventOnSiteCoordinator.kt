package com.example.blap.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinates venue admission and traffic carried by the event-specific Nearby mesh. */
internal class EventOnSiteCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val adminKeyStore: EventAdminKeyStore,
    private val meshGateway: EventMeshGateway,
    private val scope: CoroutineScope,
    private val currentState: () -> EventUiState,
    private val updateState: (((EventUiState) -> EventUiState) -> Unit),
    private val closeEventObservers: () -> Unit,
    private val clock: () -> Long,
) {

    fun enterWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) {
        val event = currentState().selectedEvent ?: return
        if ((event.visibility == EventVisibility.PRIVATE || event.requiresSignIn) &&
            !remoteRepository.hasSignedInAccount()
        ) {
            updateState { it.copy(error = "Sign in with Email or Google to enter this event.") }
            return
        }
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
            is EventEntryDecision.NeedsQr -> updateState { it.copy(notice = decision.reason) }
            is EventEntryDecision.Denied -> updateState { it.copy(error = decision.reason) }
        }
    }

    fun enterWithQr(payload: String) {
        val event = currentState().selectedEvent ?: return
        if ((event.visibility == EventVisibility.PRIVATE || event.requiresSignIn) &&
            !remoteRepository.hasSignedInAccount()
        ) {
            updateState { it.copy(error = "Sign in with Email or Google to enter this event.") }
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

    fun onPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) {
        val state = currentState()
        val event = state.events.firstOrNull { it.id == eventId } ?: return
        if (!EventOnSitePolicy.canSynchronizePeer(event, state.activeEventId, userId, accessGranted)) return
        meshGateway.synchronizeEventHistory(peerId, eventStore.getRecentChatMessages(eventId, 50))
        meshGateway.synchronizeEventAnnouncements(peerId, eventStore.getAnnouncements(eventId, 100))
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
            closeEventObservers()
            eventStore.purgeEvent(updated.id)
            meshGateway.setActiveEvent(null)
            updateState {
                it.returnToEventList(
                    events = it.events.withoutEvent(updated.id),
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
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
    }

}

/** Pure validation boundary for untrusted packets received from the event mesh. */
internal object EventOnSitePolicy {
    fun canSynchronizePeer(
        event: CommunityEvent,
        activeEventId: String?,
        peerUserId: String,
        accessGranted: Boolean,
    ): Boolean = activeEventId == event.id &&
        accessGranted &&
        (event.visibility != EventVisibility.PRIVATE || peerUserId in event.memberIds)

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
