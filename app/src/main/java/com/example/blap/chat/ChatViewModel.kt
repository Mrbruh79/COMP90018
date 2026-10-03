package com.example.blap.chat

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

class ChatViewModel(private val session: MessagingSession, private val ownsSession: Boolean = false) : androidx.lifecycle.ViewModel(), NearbyTransport.Listener {
    val uiState = session.uiState
    private val checkingConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init { session.nearbyTransport.listener = this }

    fun openConversation(peerId: String) = session.navigation.openConversation(peerId)
    fun deleteChat() {
        val peerId = session.state.value.selectedPeerId ?: return
        session.workScope.launch {
            session.store.deleteConversation(peerId)
            session.state.update { it.copy(messageDrafts = it.messageDrafts - peerId) }
            session.navigation.showConversationList()
            session.persistence.reloadConversationsNow()
            session.showNotice("Chat deleted on this device. Your contact is still saved.")
        }
    }
    fun showConversationList() {
        session.navigation.showConversationList()
    }
    fun showNotificationSettings() = session.navigation.showNotificationSettings()
    fun showError(message: String) = session.showError(message)
    fun showNotice(message: String) = session.showNotice(message)
    fun accountChanged(accountId: String) = session.cloudSync.accountChanged(accountId)
    override fun onGroupReceived(group: PrivateGroup) = session.groupManagement.onGroupReceived(group)
    override fun onCleared() {
        if (session.nearbyTransport.listener === this) session.nearbyTransport.listener = null
        if (ownsSession) session.close()
    }

    fun startChat() {
        if (session.state.value.nearbyActive) return
        when (val check = session.identityService.checkIdentity()) {
            is IdentityCheck.Invalid -> {
                session.state.update { it.copy(error = check.nameError ?: check.phoneError) }
                return
            }

            is IdentityCheck.Valid -> session.identityService.saveIdentity(check.name, check.phone)
        }
        if (session.state.value.screen == ChatScreen.WELCOME) session.navigation.showConversationList()
        val state = session.state.value
        session.state.update { it.copy(nearbyActive = true, error = null) }
        session.nearbyTransport.configureAccount(session.currentProfile().username, state.onlineAccountId)
        session.nearbyTransport.startAdvertising(state.displayName, session.localPeerId, session.localPhoneHash)
        if (session.state.value.nearbyActive) session.nearbyTransport.startDiscovery()
        // Retry optional key publication after a server-rule update, without delaying radio startup.
        session.workScope.launch { session.accountPublisher.publishAccount() }
    }

    fun connectToDevice(endpointId: String) {
        val device = session.state.value.discoveredDevices.firstOrNull { it.endpointId == endpointId }
            ?: return
        session.requestedEndpointId = endpointId
        session.state.update {
            it.copy(
                screen = ChatScreen.CONNECTING,
                authenticationDigits = null,
                error = null,
            )
        }
        session.nearbyTransport.connectToDevice(device.endpointId)
    }

    fun updateConversationSearch(query: String) {
        session.state.update { it.copy(conversationSearch = query.take(80)) }
    }

    fun sendMessage(text: String) = sendContent(ChatContent.Text(text.trim().take(ChatLimits.MAX_MESSAGE_LENGTH)))

    fun sendReply(targetId: String, text: String) {
        if (text.isBlank()) return
        val target = ChatTimeline.present(session.state.value.messages).firstOrNull {
            it.message.id == targetId && !it.deleted
        } ?: return
        val excerpt = when (val content = target.content) {
            is ChatContent.Text -> content.body
            is ChatContent.Reply -> content.body
            is ChatContent.Poll -> content.question
            is ChatContent.Voice -> "Voice message"
            else -> return
        }.take(80)
        sendContent(ChatContent.Reply(text.trim().take(500), targetId, excerpt))
    }

    fun createPoll(question: String, options: List<String>) {
        val clean = options.map(String::trim).filter(String::isNotBlank)
        if (question.isBlank() || clean.size !in 2..4 || clean.distinct().size != clean.size) {
            session.showError("Enter a question and two to four different options.")
            return
        }
        sendContent(ChatContent.Poll(question.trim().take(180), clean.map { it.take(80) }))
    }

    fun voteInPoll(pollId: String, option: Int) {
        val poll = ChatTimeline.present(session.state.value.messages).firstOrNull { it.message.id == pollId }
        val choices = (poll?.content as? ChatContent.Poll)?.options ?: return
        if (poll.deleted || option !in choices.indices) return
        sendContent(ChatContent.Vote(pollId, option))
    }

    fun editMessage(messageId: String, text: String) {
        if (text.isBlank()) return
        val original = ChatTimeline.present(session.state.value.messages).firstOrNull { it.message.id == messageId }
            ?: return
        if (original.message.author != MessageAuthor.ME || original.deleted ||
            (original.content !is ChatContent.Text && original.content !is ChatContent.Reply)) return
        sendContent(ChatContent.Edit(messageId, text.trim().take(500)))
    }

    fun deleteMessage(messageId: String) {
        val original = ChatTimeline.present(session.state.value.messages).firstOrNull { it.message.id == messageId }
            ?: return
        if (original.message.author != MessageAuthor.ME || original.deleted) return
        sendContent(ChatContent.Delete(messageId))
    }

    fun sendVoice(durationMs: Int, audio: ByteArray) {
        if (durationMs < ChatLimits.MIN_VOICE_DURATION_MS || audio.isEmpty()) return
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(audio)
        sendContent(ChatContent.Voice(durationMs.coerceAtMost(ChatLimits.MAX_VOICE_DURATION_MS), encoded))
    }

    private fun sendContent(content: ChatContent) {
        val peerId = session.state.value.selectedPeerId ?: return
        val conversation = session.state.value.conversations.firstOrNull { it.peerId == peerId } ?: return
        val cleanText = ChatFeatures.encode(content)
        val maxLength = if (content is ChatContent.Voice) ChatLimits.MAX_VOICE_ENCODED_LENGTH else ChatLimits.MAX_MESSAGE_LENGTH
        if (cleanText.isBlank() || cleanText.length > maxLength) {
            if (cleanText.length > maxLength) session.showError("This message is too long.")
            return
        }

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            peerId = peerId,
            text = cleanText,
            author = MessageAuthor.ME,
            sentAt = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
            senderId = session.localPeerId,
            senderName = session.state.value.displayName,
            senderPhoneHash = session.localPhoneHash,
            senderAccountId = session.state.value.onlineAccountId,
        )
        session.state.update { state ->
            state.copy(
                messageDrafts = if (content is ChatContent.Text || content is ChatContent.Reply)
                    state.messageDrafts - peerId else state.messageDrafts,
                messages = state.messages + message,
                conversations = updateConversationPreview(state.conversations, message),
            )
        }

        session.workScope.launch {
            session.store.saveMessage(message)
            session.persistence.reloadConversationsNow()
            session.workScope.launch { session.cloudSync.uploadMessage(message) }
            if (conversation.type != ConversationType.DIRECT && session.connectedPeers.isNotEmpty()) {
                sendStoredMessage(message)
            }
            else if (session.connectedPeers.containsKey(session.persistence.transportPeerId(peerId))) sendStoredMessage(message)
        }
    }

    fun disconnect(peerId: String) {
        session.nearbyTransport.disconnect(session.persistence.transportPeerId(peerId))
    }

    fun dismissError() {
        session.state.update { it.copy(error = null, notice = null) }
    }

    fun updateMessageDraft(text: String) {
        val peerId = session.state.value.selectedPeerId ?: return
        session.state.update { it.copy(messageDrafts = it.messageDrafts + (peerId to text.take(ChatLimits.MAX_MESSAGE_LENGTH))) }
    }

    fun updateVenueStatus(message: String, checking: Boolean = false) {
        session.state.update { it.copy(venueStatus = message, checkingVenue = checking) }
    }

    override fun onDeviceFound(device: NearbyDevice) {
        session.state.update { state ->
            val devices = state.discoveredDevices
                .filterNot { it.endpointId == device.endpointId }
                .plus(device)
                .sortedBy { it.name.lowercase() }
            state.copy(discoveredDevices = devices)
        }
    }

    override fun onDeviceLost(endpointId: String) {
        session.state.update { state ->
            state.copy(
                discoveredDevices = state.discoveredDevices.filterNot { it.endpointId == endpointId },
            )
        }
    }

    override fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String) {
        if (!session.state.value.nearbyActive) {
            session.nearbyTransport.rejectConnection(device.endpointId)
            return
        }
        if (device.peerId.isNotBlank() && session.nearbyTransport.canVerifyIdentity(device.endpointId)) {
            checkingConnections += device.endpointId
            session.workScope.launch {
                var trusted = session.dependencies.nearbyIdentities.trusted(device.peerId)
                    ?.takeIf { it.username == device.username }
                if (trusted == null) {
                    val saved = session.store.getSavedContacts().filter {
                        it.cloudUserId.isNotBlank() && it.username.equals(device.username, ignoreCase = true)
                    }.distinctBy(SavedContact::cloudUserId).singleOrNull()
                    if (saved != null && session.state.value.onlineAccountId.isNotBlank()) {
                        val account = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                            runCatching { session.cloudController?.getAccount(saved.cloudUserId) }.getOrNull()
                        }
                        if (account != null && account.peerId == device.peerId && account.username == device.username &&
                            account.nearbyPublicKey.isNotBlank()) {
                            trusted = TrustedNearbyIdentity(account.peerId, account.username, account.uid,
                                account.nearbyPublicKey, accountVerified = true)
                        }
                    }
                }
                if (!checkingConnections.remove(device.endpointId) || !session.state.value.nearbyActive) return@launch
                if (trusted != null) session.nearbyTransport.acceptKnownConnection(device.endpointId, trusted)
                else showConnectionRequest(device, authenticationDigits)
            }
            return
        }
        showConnectionRequest(device, authenticationDigits)
    }

    private fun showConnectionRequest(device: NearbyDevice, authenticationDigits: String) {
        session.state.update { state ->
            state.copy(
                connectionRequests = state.connectionRequests.filterNot { it.device.endpointId == device.endpointId } +
                    NearbyConnectionRequest(device, authenticationDigits),
                authenticationDigits = if (session.requestedEndpointId == device.endpointId) authenticationDigits
                    else state.authenticationDigits,
                error = null,
            )
        }
    }

    fun acceptConnection(endpointId: String) {
        if (session.state.value.connectionRequests.none { it.device.endpointId == endpointId }) return
        session.state.update { it.copy(connectionRequests = it.connectionRequests.filterNot { request ->
            request.device.endpointId == endpointId
        }) }
        session.nearbyTransport.acceptConnection(endpointId)
    }

    fun rejectConnection(endpointId: String) {
        session.nearbyTransport.rejectConnection(endpointId)
        onConnectionClosed(endpointId)
    }

    override fun onConnectionClosed(endpointId: String) {
        checkingConnections.remove(endpointId)
        val wasRequested = session.requestedEndpointId == endpointId
        if (wasRequested) session.requestedEndpointId = null
        session.state.update { it.copy(
            connectionRequests = it.connectionRequests.filterNot { request -> request.device.endpointId == endpointId },
            screen = if (wasRequested && it.screen == ChatScreen.CONNECTING) ChatScreen.CHATS else it.screen,
            authenticationDigits = if (wasRequested) null else it.authenticationDigits,
        ) }
    }

    override fun onConnected(peer: ConnectedPeer) {
        session.workScope.launch {
            session.connectedPeers[peer.peerId] = peer
            rememberDevice(peer)
            // The approved radio link must not wait for Firebase to become reachable.
            recordConnected(peer)
            val linkedPeer = withTimeoutOrNull(7_000L) { session.accountLookup.linkNearbyPeer(peer) } ?: peer
            if (session.connectedPeers[peer.peerId]?.endpointId != peer.endpointId) return@launch
            session.connectedPeers[peer.peerId] = linkedPeer
            rememberDevice(linkedPeer)
            if (linkedPeer != peer) recordConnected(linkedPeer)
            else {
                session.persistence.reloadSavedContactsNow()
                session.persistence.reloadConversationsNow()
                session.state.value.selectedPeerId?.let(session.persistence::reloadMessagesNow)
            }
        }
    }

    private fun rememberDevice(peer: ConnectedPeer) {
        if (peer.publicKey.isBlank()) return
        val identities = session.dependencies.nearbyIdentities
        identities.remember(TrustedNearbyIdentity(peer.peerId, peer.username, peer.accountUid, peer.publicKey,
            peer.accountVerified || identities.trusted(peer.peerId)?.accountVerified == true))
    }

    private fun recordConnected(peer: ConnectedPeer) {
        session.connectedPeers[peer.peerId] = peer
        val conversationId = session.persistence.canonicalPeerId(peer.peerId)
        val shouldOpen = session.state.value.screen == ChatScreen.CONNECTING && session.requestedEndpointId == peer.endpointId
        if (shouldOpen) session.requestedEndpointId = null

        session.state.update { state ->
            val existing = state.conversations.firstOrNull { it.peerId == conversationId }
            val conversation = (existing ?: ConversationSummary(conversationId, peer.name)).copy(
                name = peer.name,
                connected = true,
            )
            val conversations = state.conversations
                .filterNot { it.peerId == conversationId }
                .plus(conversation)
                .sortedByDescending { it.lastMessageAt }

            state.copy(
                screen = if (shouldOpen) ChatScreen.CONVERSATION else state.screen,
                selectedPeerId = if (shouldOpen) conversationId else state.selectedPeerId,
                discoveredDevices = state.discoveredDevices.filterNot { it.endpointId == peer.endpointId },
                conversations = conversations,
                directConnectionCount = session.connectedPeers.size,
                authenticationDigits = null,
                connectionRequests = state.connectionRequests.filterNot { it.device.endpointId == peer.endpointId },
                error = null,
            )
        }

        session.workScope.launch {
            session.store.savePeer(conversationId, peer.name, peer.phoneHash)
            if (peer.username.isNotBlank() && (!conversationId.startsWith("account:") || peer.accountVerified))
                session.store.savePeerUsername(conversationId, peer.username)
            if (shouldOpen) session.store.reopenConversation(conversationId)
            session.persistence.mergeConversationAlias(peer.peerId, conversationId)
            if (!peer.accountVerified && peer.username.isBlank() && peer.phoneHash.isNotBlank()) {
                session.store.linkContact(peer.phoneHash, peer.peerId)
                session.persistence.movePhoneConversationToPeer(peer.phoneHash, peer.peerId)
            }
            session.persistence.reloadConversationsNow()
            session.persistence.reloadSavedContactsNow()
            if (session.state.value.selectedPeerId == conversationId) session.persistence.reloadMessagesNow(conversationId)
            session.store.getPendingMessages(conversationId).forEach(::sendStoredMessage)
            session.groupManagement.synchronizeGroupsWith(peer.peerId)
            session.cloudSync.syncPending()
        }
    }

    override fun onMeshPeerFound(peer: GroupMember) {
        if (peer.peerId == session.localPeerId) return
        session.workScope.launch {
            session.store.saveMeshPeer(peer.peerId, peer.name, peer.phoneHash)
            if (peer.phoneHash.isNotBlank()) {
                session.store.linkContact(peer.phoneHash, peer.peerId)
                session.persistence.movePhoneConversationToPeer(peer.phoneHash, peer.peerId)
            }
            session.persistence.reloadSavedContactsNow()
            session.persistence.reloadConversationsNow()
            if (session.state.value.screen == ChatScreen.CREATING_GROUP) {
                session.groupManagement.reloadGroupContactsNow()
            }
        }
    }

    override fun onMessageReceived(message: IncomingNearbyMessage) {
        if (message.conversationId == MeshGroup.ID) return
        session.workScope.launch {
            val isDirect = message.conversationId == message.senderId
            val conversationId = if (isDirect) session.persistence.canonicalPeerId(message.senderId) else message.conversationId
            val savedMessage = ChatMessage(
                id = message.messageId,
                peerId = conversationId,
                text = message.text,
                author = if (message.senderId == session.localPeerId) MessageAuthor.ME else MessageAuthor.PEER,
                sentAt = message.sentAt,
                status = MessageStatus.DELIVERED,
                senderId = message.senderId,
                senderName = message.senderName,
                senderPhoneHash = message.senderPhoneHash,
                senderAccountId = session.connectedPeers[message.senderId]?.takeIf { it.accountVerified }?.accountUid.orEmpty(),
            )
            val isPrivateMember = !isDirect &&
                session.store.isGroupMember(message.conversationId, session.localPeerId, session.localPhoneHash) &&
                session.store.isGroupMember(
                    message.conversationId,
                    message.senderId,
                    message.senderPhoneHash,
                )
            if (!isDirect && !isPrivateMember) return@launch

            if (isDirect) {
                session.store.savePeer(conversationId, message.senderName, message.senderPhoneHash)
            }
            val inserted = session.store.saveMessage(savedMessage)
            if (inserted) {
                session.persistence.reloadConversationsNow()
                val currentConversation = if (isDirect) session.persistence.canonicalPeerId(message.senderId) else conversationId
                if (session.state.value.selectedPeerId == currentConversation) {
                    session.persistence.reloadMessagesNow(currentConversation)
                }
                session.persistence.notifyIncoming(savedMessage)
            }
            session.nearbyTransport.acknowledgeMessage(
                message.conversationId,
                message.senderId,
                message.messageId,
            )
        }
    }

    override fun onMessageSent(peerId: String, messageId: String) {
        session.persistence.setMessageStatus(session.persistence.canonicalPeerId(peerId), messageId, MessageStatus.SENT)
    }

    override fun onMessageDelivered(peerId: String, messageId: String) {
        session.persistence.setMessageStatus(session.persistence.canonicalPeerId(peerId), messageId, MessageStatus.DELIVERED)
    }

    override fun onDisconnected(peerId: String) {
        val canonical = session.persistence.canonicalPeerId(peerId)
        session.connectedPeers.remove(peerId)
        session.state.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    when (conversation.peerId) {
                        canonical -> conversation.copy(connected = false)
                        else -> if (conversation.type != ConversationType.DIRECT) {
                            conversation.copy(connected = session.connectedPeers.isNotEmpty())
                        } else {
                            conversation
                        }
                    }
                },
                directConnectionCount = session.connectedPeers.size,
            )
        }
    }

    override fun onError(message: String) {
        session.state.update { state ->
            val nextState = if (state.screen == ChatScreen.CONNECTING) {
                session.requestedEndpointId = null
                ChatScreen.CHATS
            } else {
                state.screen
            }
            state.copy(screen = nextState, authenticationDigits = null, error = message)
        }
    }

    override fun onNearbyUnavailable(message: String) {
        stopChat()
        session.showError(message)
    }

    private fun sendStoredMessage(message: ChatMessage) {
        session.nearbyTransport.sendMessage(
            OutgoingNearbyMessage(
                messageId = message.id,
                peerId = session.persistence.transportPeerId(message.peerId),
                text = message.text,
                sentAt = message.sentAt,
                isGroup = session.state.value.conversations.firstOrNull {
                    it.peerId == message.peerId
                }?.type != ConversationType.DIRECT,
            ),
        )
    }

    private fun updateConversationPreview(
        conversations: List<ConversationSummary>,
        message: ChatMessage,
    ): List<ConversationSummary> {
        val oldConversation = conversations.firstOrNull { it.peerId == message.peerId } ?: return conversations
        val updated = oldConversation.copy(
            lastMessage = ChatFeatures.preview(message.text),
            lastMessageAt = message.sentAt,
        )
        return conversations.filterNot { it.peerId == message.peerId }.plus(updated)
            .sortedByDescending { it.lastMessageAt }
    }

    fun stopChat() {
        checkingConnections.clear()
        session.requestedEndpointId = null
        session.nearbyTransport.stop()
        session.connectedPeers.clear()
        session.state.update {
            it.copy(
                nearbyActive = false,
                screen = if (it.screen == ChatScreen.CONNECTING) ChatScreen.CHATS else it.screen,
                discoveredDevices = emptyList(),
                authenticationDigits = null,
                connectionRequests = emptyList(),
                directConnectionCount = 0,
                conversations = it.conversations.map { conversation ->
                    conversation.copy(connected = false)
                },
            )
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = ChatLimits.MAX_NAME_LENGTH
        const val MAX_MESSAGE_LENGTH = ChatLimits.MAX_MESSAGE_LENGTH
        const val MAX_VOICE_ENCODED_LENGTH = ChatLimits.MAX_VOICE_ENCODED_LENGTH
        const val MIN_VOICE_DURATION_MS = ChatLimits.MIN_VOICE_DURATION_MS
        const val MAX_VOICE_DURATION_MS = ChatLimits.MAX_VOICE_DURATION_MS
        const val MAX_GROUP_NAME_LENGTH = ChatLimits.MAX_GROUP_NAME_LENGTH
        const val MAX_PHONE_LENGTH = ChatLimits.MAX_PHONE_LENGTH
        const val MAX_EMAIL_LENGTH = ChatLimits.MAX_EMAIL_LENGTH
        const val MAX_BIO_LENGTH = ChatLimits.MAX_BIO_LENGTH
        const val MAX_URL_LENGTH = ChatLimits.MAX_URL_LENGTH
    }
}
