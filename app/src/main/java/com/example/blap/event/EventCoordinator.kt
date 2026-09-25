package com.example.blap.event

import com.example.blap.chat.IdentityStore
import com.example.blap.chat.NearbyChatController
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
}

data class EventUiState(
    val page: EventPage = EventPage.LIST,
    val events: List<CommunityEvent> = emptyList(),
    val selectedEventId: String? = null,
    val membership: EventMembership? = null,
    val members: List<EventMembership> = emptyList(),
    val announcements: List<EventAnnouncement> = emptyList(),
    val chatMessages: List<EventChatMessage> = emptyList(),
    val activeEventId: String? = null,
    val checkInQrPayload: String? = null,
    val loading: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val selectedEvent: CommunityEvent?
        get() = events.firstOrNull { it.id == selectedEventId }
}

class EventCoordinator(
    private val eventStore: EventStore,
    private val remoteRepository: EventRemoteRepository,
    private val adminKeyStore: EventAdminKeyStore,
    private val identityStore: IdentityStore,
    private val nearbyController: NearbyChatController,
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

    init {
        scope.launch {
            runCatching {
                requireUserId()
                startEventObserver()
            }.onFailure { failure ->
                _uiState.update { state ->
                    state.copy(error = failure.readableMessage("Could not connect to events"))
                }
            }
        }
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

    fun showList() {
        _uiState.update {
            it.copy(
                page = EventPage.LIST,
                selectedEventId = null,
                membership = null,
                members = emptyList(),
                announcements = emptyList(),
                chatMessages = emptyList(),
                checkInQrPayload = null,
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
                    _uiState.update { it.copy(events = eventStore.getEvents(), loading = false) }
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
        createRequestInFlight = true
        _uiState.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                runCatching {
                    val userId = requireUserId()
                    val keys = adminKeyStore.getOrCreate(userId)
                    val event = CommunityEvent(
                        id = UUID.randomUUID().toString(),
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
                        adminPublicKeys = mapOf(userId to EventCheckInCodec.encodePublicKey(keys.public)),
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
        _uiState.update {
            it.copy(
                page = EventPage.DETAIL,
                selectedEventId = event.id,
                announcements = eventStore.getAnnouncements(event.id),
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
        scope.launch {
            val userId = runCatching { requireUserId() }.getOrNull() ?: return@launch
            _uiState.update { it.copy(membership = eventStore.getMembership(event.id, userId)) }
            refreshMembers(event.id)
            refreshAnnouncements(event.id)
        }
    }

    fun joinSelectedEvent() {
        val event = _uiState.value.selectedEvent ?: return
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
                    val updatedEvent = event.copy(adminIds = event.adminIds - userId)
                    eventStore.saveEvent(updatedEvent)
                    _uiState.update {
                        it.copy(
                            events = eventStore.getEvents(),
                            members = it.members.map { member ->
                                if (member.userId == userId) member.copy(blockedAt = blockedAt) else member
                            },
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

    fun enterWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) {
        val event = _uiState.value.selectedEvent ?: return
        val membership = _uiState.value.membership
        when (val decision = EventAccessPolicy.evaluateGps(
            event,
            membership,
            latitude,
            longitude,
            accuracyMetres,
            clock(),
        )) {
            is EventEntryDecision.Allowed -> activateOnSite(event, requireNotNull(membership), decision.method)
            is EventEntryDecision.NeedsQr -> _uiState.update { it.copy(notice = decision.reason) }
            is EventEntryDecision.Denied -> _uiState.update { it.copy(error = decision.reason) }
        }
    }

    fun enterWithQr(payload: String) {
        val event = _uiState.value.selectedEvent ?: return
        val credential = event.adminPublicKeys.entries.firstNotNullOfOrNull { (adminId, encodedKey) ->
            runCatching {
                EventCheckInCodec.verify(
                    payload,
                    event.id,
                    EventCheckInCodec.decodePublicKey(encodedKey),
                    clock(),
                )?.takeIf { it.adminId == adminId }
            }.getOrNull()
        }
        if (credential == null) {
            _uiState.update { it.copy(error = "This venue check-in QR is invalid or expired.") }
            return
        }
        val existing = _uiState.value.membership
        if (existing != null && existing.leftAt == null) {
            applyQrDecision(event, existing, credential)
            return
        }
        scope.launch {
            val membership = runCatching {
                val userId = requireUserId()
                EventMembership(
                    eventId = event.id,
                    userId = userId,
                    displayName = identityStore.getDisplayName(),
                    role = EventRole.ATTENDEE,
                    joinedAt = clock(),
                )
            }.getOrElse { failure ->
                _uiState.update { it.copy(error = failure.readableMessage("The event could not be joined")) }
                return@launch
            }
            eventStore.saveMembership(membership)
            _uiState.update { it.copy(membership = membership) }
            applyQrDecision(event, membership, credential)
            runCatching { remoteRepository.joinEvent(membership) }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(notice = "Joined from the venue QR. Membership will sync when online.")
                    }
                }
        }
    }

    fun createVenueCheckInQr(): String? {
        val event = _uiState.value.selectedEvent ?: return null
        val membership = _uiState.value.membership ?: return null
        if (!membership.isAdmin || !event.isAdmin(membership.userId) || !event.isActive(clock())) return null
        val keys = adminKeyStore.get(membership.userId) ?: return null
        return EventCheckInCodec.create(event.id, membership.userId, keys.private, issuedAt = clock())
    }

    fun showVenueCheckInQr() {
        val payload = createVenueCheckInQr()
        _uiState.update {
            if (payload == null) it.copy(error = "A check-in QR is available to admins while the event is active.")
            else it.copy(checkInQrPayload = payload, error = null)
        }
    }

    fun hideVenueCheckInQr() {
        _uiState.update { it.copy(checkInQrPayload = null) }
    }

    fun sendOnSiteMessage(text: String) {
        val state = _uiState.value
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        val cleanText = text.trim().take(1_000)
        if (cleanText.isBlank()) return
        if (state.activeEventId != event.id || !event.isActive(clock()) || !membership.canParticipate) {
            _uiState.update { it.copy(error = "On-site chat is not currently active.") }
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
        nearbyController.sendEventChatMessage(message)
        _uiState.update { it.copy(chatMessages = eventStore.getChatMessages(event.id)) }
    }

    fun showSavedOnSiteHistory() {
        val event = _uiState.value.selectedEvent ?: return
        _uiState.update {
            it.copy(
                page = EventPage.ON_SITE_CHAT,
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
    }

    fun onEventPeerAvailable(peerId: String, eventId: String) {
        if (_uiState.value.activeEventId != eventId) return
        nearbyController.synchronizeEventHistory(peerId, eventStore.getRecentChatMessages(eventId, 50))
        nearbyController.synchronizeEventAnnouncements(peerId, eventStore.getAnnouncements(eventId, 100))
    }

    fun onEventChatMessageReceived(message: EventChatMessage) {
        val state = _uiState.value
        val event = state.events.firstOrNull { it.id == message.eventId } ?: return
        if (state.activeEventId != event.id || !event.isActive(clock())) return
        val membership = state.membership ?: return
        if (!membership.canParticipate) return
        if (state.members.isNotEmpty() && state.members.none {
                it.userId == message.senderId && it.canParticipate
            }
        ) return
        if (eventStore.saveChatMessage(message)) {
            _uiState.update { it.copy(chatMessages = eventStore.getChatMessages(event.id)) }
        }
    }

    fun onEventAnnouncementReceived(announcement: EventAnnouncement) {
        val event = _uiState.value.events.firstOrNull { it.id == announcement.eventId }
            ?: eventStore.getEvent(announcement.eventId)
            ?: return
        if (announcement.adminId !in event.adminIds) return
        val publicKey = event.adminPublicKeys[announcement.adminId]
            ?.let { runCatching { EventCheckInCodec.decodePublicKey(it) }.getOrNull() }
            ?: return
        if (!EventAnnouncementSigner.verify(announcement, publicKey)) return
        if (eventStore.saveAnnouncement(announcement)) {
            _uiState.update { state ->
                if (state.selectedEventId == event.id) {
                    state.copy(announcements = eventStore.getAnnouncements(event.id))
                } else state
            }
        }
    }

    fun onEventMutationReceived(mutation: EventMutation) {
        val existing = eventStore.getEvent(mutation.event.id) ?: return
        if (mutation.event.createdBy != existing.createdBy) return
        if (mutation.adminId !in existing.adminIds || mutation.event.updatedAt <= existing.updatedAt) return
        if (mutation.event.deletedAt != null && mutation.adminId != existing.createdBy) return
        val publicKey = existing.adminPublicKeys[mutation.adminId]
            ?.let { runCatching { EventCheckInCodec.decodePublicKey(it) }.getOrNull() }
            ?: return
        if (!EventMutationSigner.verify(mutation, publicKey)) return
        if (mutation.event.isDeleted) {
            eventStore.purgeEvent(mutation.event.id)
            nearbyController.setActiveEvent(null)
            _uiState.update {
                EventUiState(
                    events = eventStore.getEvents(),
                    notice = "The event and its local data were deleted by the primary admin.",
                )
            }
            return
        }
        eventStore.saveEvent(mutation.event)
        _uiState.update { state ->
            state.copy(
                events = eventStore.getEvents(),
                notice = "Event details were updated.",
            )
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(error = null, notice = null) }
    }

    fun close() {
        eventObserver?.close()
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

    private fun activateOnSite(
        event: CommunityEvent,
        membership: EventMembership,
        method: EventAccessMethod,
    ) {
        val checkedIn = membership.copy(accessMethod = method, checkedInAt = clock())
        eventStore.saveMembership(checkedIn)
        nearbyController.setActiveEvent(event.id)
        _uiState.update {
            it.copy(
                page = EventPage.ON_SITE_CHAT,
                membership = checkedIn,
                activeEventId = event.id,
                chatMessages = eventStore.getChatMessages(event.id),
                error = null,
            )
        }
    }

    private fun applyQrDecision(
        event: CommunityEvent,
        membership: EventMembership,
        credential: EventCheckInCredential,
    ) {
        when (val decision = EventAccessPolicy.evaluateQr(event, membership, credential, clock())) {
            is EventEntryDecision.Allowed -> activateOnSite(event, membership, decision.method)
            is EventEntryDecision.NeedsQr -> _uiState.update { it.copy(notice = decision.reason) }
            is EventEntryDecision.Denied -> _uiState.update { it.copy(error = decision.reason) }
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
                    } else if (cachedMembership?.canParticipate == true) {
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

    private suspend fun requireUserId(): String = cachedUserId ?: remoteRepository
        .requireUserId()
        .also { cachedUserId = it }

    private fun Throwable.readableMessage(prefix: String): String =
        "$prefix: ${localizedMessage?.takeIf(String::isNotBlank) ?: "unknown error"}"
}
