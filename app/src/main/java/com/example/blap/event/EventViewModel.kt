package com.example.blap.event

import androidx.lifecycle.ViewModel
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.MessagingSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Owns event actions, event state and mesh callbacks without routing through chat. */
class EventViewModel(private val session: MessagingSession) : ViewModel(), EventMeshGateway.Listener {
    private val eventScope = CoroutineScope(SupervisorJob() + session.dependencies.ioDispatcher)
    private val services = session.dependencies.events
    private val eventCoordinator = services?.let {
        EventCoordinator(it.store, it.remoteRepository, it.adminKeyStore, session.identityStore,
            it.meshGateway, eventScope)
    }
    private val closed = AtomicBoolean(false)
    private var observedAccountId = session.dependencies.accountId
    val uiState = eventCoordinator?.uiState ?: MutableStateFlow(EventUiState()).asStateFlow()

    init {
        session.closeEvents = ::dispose
        services?.meshGateway?.eventListener = this
        eventScope.launch {
            session.uiState.map { it.onlineAccountId }.distinctUntilChanged().collect { accountId ->
                if (accountId != observedAccountId) {
                    observedAccountId = accountId
                    eventCoordinator?.accountChanged()
                }
            }
        }
    }

    fun showEvents() {
        session.state.update { it.copy(screen = ChatScreen.EVENTS, error = null) }
        eventCoordinator?.showList()
    }

    fun beginCreateEvent() = eventCoordinator?.beginCreate() ?: Unit

    fun beginEditEvent() = eventCoordinator?.beginEdit() ?: Unit

    fun createEvent(request: EventCreateRequest) = createEvent(
        request.title, request.description, request.venueName, request.latitude, request.longitude,
        request.radiusMetres, request.startsAt, request.endsAt, request.visibility, request.requiresSignIn,
    )

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
    ) = eventCoordinator?.createEvent(
        title,
        description,
        venueName,
        latitude,
        longitude,
        radiusMetres,
        startsAt,
        endsAt,
        visibility,
        requiresSignIn,
    ) ?: Unit

    fun openEvent(eventId: String) = eventCoordinator?.openEvent(eventId) ?: Unit

    fun updateSelectedEvent(request: EventCreateRequest) = eventCoordinator?.updateSelectedEvent(request) ?: Unit

    fun deleteSelectedEvent() = eventCoordinator?.deleteSelectedEvent() ?: Unit

    fun joinSelectedEvent() = eventCoordinator?.joinSelectedEvent() ?: Unit

    fun inviteToSelectedEvent(identifier: String) = eventCoordinator?.inviteToSelectedEvent(identifier) ?: Unit

    fun searchSelectedEventParticipant(identifier: String) =
        eventCoordinator?.searchSelectedEventParticipant(identifier) ?: Unit

    fun acceptEventInvitation(invitationId: String) = eventCoordinator?.acceptInvitation(invitationId) ?: Unit

    fun declineEventInvitation(invitationId: String) = eventCoordinator?.declineInvitation(invitationId) ?: Unit

    fun revokeEventInvitation(invitationId: String) = eventCoordinator?.revokeInvitation(invitationId) ?: Unit

    fun leaveSelectedEvent() = eventCoordinator?.leaveSelectedEvent() ?: Unit

    fun promoteEventMember(userId: String) = eventCoordinator?.promoteMemberToCoAdmin(userId) ?: Unit

    fun removeEventMember(userId: String) = eventCoordinator?.blockMember(userId) ?: Unit

    fun showEventAnnouncements() = eventCoordinator?.showAnnouncements() ?: Unit

    fun publishEventAnnouncement(text: String) = eventCoordinator?.publishAnnouncement(text) ?: Unit

    fun showEventDiscussion() = eventCoordinator?.showDiscussion() ?: Unit

    fun loadMoreEventDiscussion() = eventCoordinator?.loadMoreDiscussionRoots() ?: Unit

    fun openEventDiscussionThread(threadId: String) = eventCoordinator?.openDiscussionThread(threadId) ?: Unit

    fun createEventDiscussionComment(text: String, parentId: String?) =
        eventCoordinator?.createDiscussionComment(text, parentId) ?: Unit

    fun toggleEventDiscussionLike(commentId: String) =
        eventCoordinator?.toggleDiscussionLike(commentId) ?: Unit

    fun deleteEventDiscussionComment(commentId: String) =
        eventCoordinator?.deleteDiscussionComment(commentId) ?: Unit

    fun enterEventWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) =
        eventCoordinator?.enterWithGps(latitude, longitude, accuracyMetres) ?: Unit

    fun enterEventWithQr(payload: String) = eventCoordinator?.enterWithQr(payload) ?: Unit

    fun requestEventAdminAccess() = eventCoordinator?.requestAdminOnSiteAccess() ?: Unit

    fun approveEventAdminAccess(requestId: String) = eventCoordinator?.approveOnSiteAccess(requestId) ?: Unit

    fun createEventCheckInQr(): String? = eventCoordinator?.createVenueCheckInQr()

    fun showEventCheckInQr() = eventCoordinator?.showVenueCheckInQr() ?: Unit

    fun hideEventCheckInQr() = eventCoordinator?.hideVenueCheckInQr() ?: Unit

    fun sendEventMessage(text: String) = eventCoordinator?.sendOnSiteMessage(text) ?: Unit

    fun showSavedEventChat() = eventCoordinator?.showSavedOnSiteHistory() ?: Unit

    fun eventBack() = eventCoordinator?.back() ?: Unit

    fun dismissEventMessage() = eventCoordinator?.dismissMessage() ?: Unit

    override fun onEventPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) {
        eventCoordinator?.onEventPeerAvailable(peerId, eventId, userId, accessGranted)
    }

    override fun onEventChatMessageReceived(message: EventChatMessage) {
        eventCoordinator?.onEventChatMessageReceived(message)
    }

    override fun onEventAnnouncementReceived(announcement: EventAnnouncement) {
        eventCoordinator?.onEventAnnouncementReceived(announcement)
    }

    override fun onEventMutationReceived(mutation: EventMutation) {
        eventCoordinator?.onEventMutationReceived(mutation)
    }

    override fun onEventAccessRequestReceived(request: EventAccessRequest) {
        eventCoordinator?.onEventAccessRequestReceived(request)
    }

    override fun onEventAccessGrantReceived(grant: EventAccessGrant) {
        eventCoordinator?.onEventAccessGrantReceived(grant)
    }


    private fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        if (services?.meshGateway?.eventListener === this) services.meshGateway.eventListener = null
        eventScope.cancel()
        eventCoordinator?.close()
    }
    override fun onCleared() = dispose()
}
