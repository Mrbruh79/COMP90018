package com.example.blap.event

import com.example.blap.chat.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Handles event participation, invitations and attendee administration. */
internal class EventMembershipCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val identityStore: IdentityStore,
    private val meshGateway: EventMeshGateway,
    private val scope: CoroutineScope,
    private val requireUserId: suspend () -> String,
    private val currentState: () -> EventUiState,
    private val updateState: (((EventUiState) -> EventUiState) -> Unit),
    private val deletePrimaryAdminEvent: () -> Unit,
    private val closeEventObservers: () -> Unit,
    private val clearPendingOnSiteAccess: (String?) -> Unit,
    private val clock: () -> Long,
) {
    private var leaveRequestInFlight = false

    fun joinSelectedEvent() {
        val event = currentState().selectedEvent ?: return
        EventMembershipPolicy.joinError(event, remoteRepository.hasSignedInAccount())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val userId = requireUserId()
                val membership = EventMembershipPolicy.joinedMembership(
                    event = event,
                    userId = userId,
                    displayName = identityStore.getDisplayName(),
                    existing = eventStore.getMembership(event.id, userId),
                    joinedAt = clock(),
                )
                eventStore.saveMembership(membership)
                remoteRepository.joinEvent(membership)
                membership
            }.onSuccess { membership ->
                updateState { it.copy(membership = membership, loading = false, notice = "Joined event.") }
            }.onFailure { failure ->
                updateState { it.copy(loading = false, error = failure.readableEventMessage("Could not join event")) }
            }
        }
    }

    fun invite(identifier: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventMembershipPolicy.invitationError(event, membership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        if (identifier.isBlank()) return
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                val invitee = remoteRepository.findInvitee(identifier)
                    ?: throw IllegalArgumentException(
                        "No CommonGround account matched that exact username or verified email.",
                    )
                if (invitee.uid in event.memberIds) {
                    throw IllegalArgumentException("That account is already an event member.")
                }
                val invitation = EventMembershipPolicy.invitation(event, membership, invitee, clock())
                remoteRepository.invite(invitation)
                invitation
            }.onSuccess { invitation ->
                updateState {
                    it.copy(
                        eventInvitations = (it.eventInvitations.filterNot { old -> old.id == invitation.id } + invitation)
                            .sortedBy(EventInvitation::recipientName),
                        loading = false,
                        notice = "Invitation sent to @${invitation.recipientUsername}.",
                    )
                }
            }.onFailure { failure ->
                updateState {
                    it.copy(loading = false, error = failure.readableEventMessage("Invitation could not be sent"))
                }
            }
        }
    }

    fun searchParticipant(identifier: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        EventMembershipPolicy.participantAdminError(event, localMembership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        if (identifier.isBlank()) return
        updateState { it.copy(loading = true, error = null, participantSearchResult = null) }
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
                EventMembershipPolicy.removalError(localMembership, target)?.let { error ->
                    throw IllegalArgumentException(error)
                }
                members to EventParticipantSearchResult(target, account.username)
            }.onSuccess { (members, result) ->
                updateState {
                    it.copy(
                        members = members,
                        participantSearchResult = result,
                        loading = false,
                        notice = "Participant found.",
                    )
                }
            }.onFailure { failure ->
                updateState {
                    it.copy(
                        participantSearchResult = null,
                        loading = false,
                        error = failure.readableEventMessage("Participant search failed"),
                    )
                }
            }
        }
    }

    fun acceptInvitation(invitationId: String) {
        val invitation = currentState().invitations.firstOrNull { it.id == invitationId } ?: return
        if (!invitation.isPending) {
            updateState { it.copy(error = "This invitation is no longer available.") }
            return
        }
        updateState { it.copy(loading = true, error = null) }
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
            }.onSuccess {
                updateState { state ->
                    state.copy(
                        events = eventStore.getEvents(),
                        invitations = state.invitations.filterNot { it.id == invitationId },
                        loading = false,
                        notice = "Private event invitation accepted.",
                    )
                }
            }.onFailure { failure ->
                updateState {
                    it.copy(loading = false, error = failure.readableEventMessage("Invitation could not be accepted"))
                }
            }
        }
    }

    fun declineInvitation(invitationId: String) {
        val invitation = currentState().invitations.firstOrNull { it.id == invitationId } ?: return
        scope.launch {
            runCatching { remoteRepository.declineInvitation(invitation) }
                .onSuccess {
                    updateState { state ->
                        state.copy(
                            invitations = state.invitations.filterNot { it.id == invitationId },
                            notice = "Invitation declined.",
                        )
                    }
                }
                .onFailure { failure ->
                    updateState { it.copy(error = failure.readableEventMessage("Invitation could not be declined")) }
                }
        }
    }

    fun revokeInvitation(invitationId: String) {
        val invitation = currentState().eventInvitations.firstOrNull { it.id == invitationId } ?: return
        scope.launch {
            runCatching { remoteRepository.revokeInvitation(invitation) }
                .onSuccess {
                    updateState { state ->
                        state.copy(
                            eventInvitations = state.eventInvitations.map {
                                if (it.id == invitationId) it.copy(status = EventInvitationStatus.REVOKED) else it
                            },
                            notice = "Invitation revoked.",
                        )
                    }
                }
                .onFailure { failure ->
                    updateState { it.copy(error = failure.readableEventMessage("Invitation could not be revoked")) }
                }
        }
    }

    fun leaveSelectedEvent() {
        if (leaveRequestInFlight) return
        val event = currentState().selectedEvent ?: return
        val membership = currentState().membership ?: return
        if (membership.role == EventRole.PRIMARY_ADMIN && event.createdBy == membership.userId) {
            deletePrimaryAdminEvent()
            return
        }
        val leftAt = clock()
        leaveRequestInFlight = true
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching { remoteRepository.leaveEvent(event, membership, leftAt) }
                    .onSuccess {
                        closeEventObservers()
                        clearPendingOnSiteAccess(event.id)
                        meshGateway.setActiveEvent(null)
                        eventStore.purgeEvent(event.id)
                        EventMembershipPolicy.eventVisibleAfterDeparture(event, membership.userId)
                            ?.let(eventStore::saveEvent)
                        updateState { state ->
                            EventUiState(
                                events = eventStore.getEvents(),
                                invitations = state.invitations,
                                currentUserId = state.currentUserId,
                                notice = "You left the event. All local event data was deleted.",
                            )
                        }
                    }
                    .onFailure { failure ->
                        updateState {
                            it.copy(
                                loading = false,
                                error = failure.readableEventMessage("The event could not be left"),
                            )
                        }
                    }
            } finally {
                leaveRequestInFlight = false
            }
        }
    }

    fun promoteToCoAdmin(userId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        EventMembershipPolicy.promotionError(event, localMembership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val target = state.members.firstOrNull { it.userId == userId && it.canParticipate } ?: return
        scope.launch {
            runCatching { remoteRepository.promoteToCoAdmin(event.id, userId) }
                .onSuccess {
                    eventStore.saveEvent(event.copy(adminIds = event.adminIds + userId))
                    updateState {
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
                    updateState { it.copy(error = failure.readableEventMessage("Co-admin could not be added")) }
                }
        }
    }

    fun blockMember(userId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val localMembership = state.membership ?: return
        EventMembershipPolicy.removalAdminError(event, localMembership)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val target = state.members.firstOrNull { it.userId == userId } ?: return
        EventMembershipPolicy.removalError(localMembership, target)?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val blockedAt = clock()
        scope.launch {
            runCatching { remoteRepository.blockMember(event.id, userId, blockedAt) }
                .onSuccess {
                    eventStore.saveEvent(
                        event.copy(
                            adminIds = event.adminIds - userId,
                            memberIds = event.memberIds - userId,
                            adminPublicKeys = event.adminPublicKeys - userId,
                        ),
                    )
                    updateState {
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
                    updateState { it.copy(error = failure.readableEventMessage("Member could not be removed")) }
                }
        }
    }

}

/** Pure membership rules and model construction shared by membership operations. */
internal object EventMembershipPolicy {
    fun eventVisibleAfterDeparture(event: CommunityEvent, userId: String): CommunityEvent? {
        if (event.visibility != EventVisibility.PUBLIC || event.createdBy == userId) return null
        return event.copy(
            adminIds = event.adminIds - userId,
            memberIds = event.memberIds - userId,
            adminPublicKeys = event.adminPublicKeys - userId,
        )
    }

    fun joinError(event: CommunityEvent, hasSignedInAccount: Boolean): String? = when {
        event.visibility == EventVisibility.PRIVATE ->
            "Private events can only be joined by accepting an invitation."
        event.requiresSignIn && !hasSignedInAccount ->
            "Sign in with Email or Google to join this protected event."
        event.isDeleted -> "This event has been deleted by its admin."
        else -> null
    }

    fun invitationError(event: CommunityEvent, membership: EventMembership): String? =
        if (event.visibility != EventVisibility.PRIVATE || !membership.isAdmin || !event.isAdmin(membership.userId)) {
            "Only a private-event admin can send invitations."
        } else null

    fun participantAdminError(event: CommunityEvent, membership: EventMembership): String? =
        if (!membership.isAdmin || !event.isAdmin(membership.userId)) {
            "Only event admins can search participants."
        } else null

    fun removalAdminError(event: CommunityEvent, membership: EventMembership): String? =
        if (!membership.isAdmin || !event.isAdmin(membership.userId)) {
            "Only event admins can remove members."
        } else null

    fun promotionError(event: CommunityEvent, membership: EventMembership): String? =
        if (membership.role != EventRole.PRIMARY_ADMIN || event.createdBy != membership.userId) {
            "Only the primary admin can appoint co-admins."
        } else null

    fun removalError(localMembership: EventMembership, target: EventMembership): String? =
        if (target.role == EventRole.PRIMARY_ADMIN || target.userId == localMembership.userId) {
            "The primary admin cannot be removed."
        } else null

    fun joinedMembership(
        event: CommunityEvent,
        userId: String,
        displayName: String,
        existing: EventMembership?,
        joinedAt: Long,
    ): EventMembership = EventMembership(
        eventId = event.id,
        userId = userId,
        displayName = displayName,
        role = existing?.role ?: EventRole.ATTENDEE,
        joinedAt = existing?.joinedAt ?: joinedAt,
    )

    fun invitation(
        event: CommunityEvent,
        inviter: EventMembership,
        invitee: EventInvitee,
        createdAt: Long,
    ): EventInvitation = EventInvitation(
        id = "${event.id}_${invitee.uid}",
        eventId = event.id,
        eventTitle = event.title,
        inviterUid = inviter.userId,
        inviterName = inviter.displayName,
        recipientUid = invitee.uid,
        recipientName = invitee.displayName,
        recipientUsername = invitee.username,
        startsAt = event.startsAt,
        endsAt = event.endsAt,
        createdAt = createdAt,
        expiresAt = event.endsAt,
    )
}
