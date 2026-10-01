package com.example.blap.chat

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class ChatViewModel(private val session: MessagingSession, private val ownsSession: Boolean = false) : androidx.lifecycle.ViewModel(), NearbyTransport.Listener {
    val uiState = session.uiState

    init { session.nearbyTransport.listener = this }

    fun openConversation(peerId: String) = session.navigation.openConversation(peerId)
    fun showConversationList() { session.requestedEndpointId = null; session.navigation.showConversationList() }
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
        session.nearbyTransport.startAdvertising(state.displayName, session.localPeerId, session.localPhoneHash)
        if (session.state.value.nearbyActive) session.nearbyTransport.startDiscovery()
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
            else if (session.connectedPeers.containsKey(peerId)) sendStoredMessage(message)
        }
    }

    fun disconnect(peerId: String) {
        session.nearbyTransport.disconnect(peerId)
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
        session.state.update { state ->
            if (session.requestedEndpointId == device.endpointId && state.screen == ChatScreen.CONNECTING) {
                state.copy(
                    authenticationDigits = authenticationDigits,
                    error = null,
                )
            } else state
        }
    }

    override fun onConnected(peer: ConnectedPeer) {
        session.connectedPeers[peer.peerId] = peer
        val shouldOpen = session.state.value.screen == ChatScreen.CONNECTING && session.requestedEndpointId == peer.endpointId
        if (shouldOpen) session.requestedEndpointId = null

        session.state.update { state ->
            val existing = state.conversations.firstOrNull { it.peerId == peer.peerId }
            val conversation = (existing ?: ConversationSummary(peer.peerId, peer.name)).copy(
                name = peer.name,
                connected = true,
            )
            val conversations = state.conversations
                .filterNot { it.peerId == peer.peerId }
                .plus(conversation)
                .sortedByDescending { it.lastMessageAt }

            state.copy(
                screen = if (shouldOpen) ChatScreen.CONVERSATION else state.screen,
                selectedPeerId = if (shouldOpen) peer.peerId else state.selectedPeerId,
                discoveredDevices = state.discoveredDevices.filterNot { it.endpointId == peer.endpointId },
                conversations = conversations,
                directConnectionCount = session.connectedPeers.size,
                authenticationDigits = null,
                error = null,
            )
        }

        session.workScope.launch {
            session.store.savePeer(peer.peerId, peer.name, peer.phoneHash)
            session.store.linkContact(peer.phoneHash, peer.peerId)
            if (peer.phoneHash.isNotBlank()) {
                session.persistence.movePhoneConversationToPeer(peer.phoneHash, peer.peerId)
            }
            session.persistence.reloadConversationsNow()
            session.persistence.reloadSavedContactsNow()
            if (session.state.value.selectedPeerId == peer.peerId) session.persistence.reloadMessagesNow(peer.peerId)
            session.store.getPendingMessages(peer.peerId).forEach(::sendStoredMessage)
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
        val savedMessage = ChatMessage(
            id = message.messageId,
            peerId = message.conversationId,
            text = message.text,
            author = if (message.senderId == session.localPeerId) MessageAuthor.ME else MessageAuthor.PEER,
            sentAt = message.sentAt,
            status = MessageStatus.DELIVERED,
            senderId = message.senderId,
            senderName = message.senderName,
            senderPhoneHash = message.senderPhoneHash,
        )

        session.workScope.launch {
            val isOpenMesh = message.conversationId == MeshGroup.ID
            val isDirect = message.conversationId == message.senderId
            val isPrivateMember = !isOpenMesh && !isDirect &&
                session.store.isGroupMember(message.conversationId, session.localPeerId, session.localPhoneHash) &&
                session.store.isGroupMember(
                    message.conversationId,
                    message.senderId,
                    message.senderPhoneHash,
                )
            if (!isOpenMesh && !isDirect && !isPrivateMember) return@launch

            if (isOpenMesh) {
                session.store.savePeer(MeshGroup.ID, MeshGroup.NAME)
            } else if (isDirect) {
                session.store.savePeer(message.senderId, message.senderName, message.senderPhoneHash)
            }
            val inserted = session.store.saveMessage(savedMessage)
            if (inserted) {
                session.persistence.reloadConversationsNow()
                if (session.state.value.selectedPeerId == message.conversationId) {
                    session.persistence.reloadMessagesNow(message.conversationId)
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
        session.persistence.setMessageStatus(peerId, messageId, MessageStatus.SENT)
    }

    override fun onMessageDelivered(peerId: String, messageId: String) {
        session.persistence.setMessageStatus(peerId, messageId, MessageStatus.DELIVERED)
    }

    override fun onDisconnected(peerId: String) {
        session.connectedPeers.remove(peerId)
        session.state.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    when (conversation.peerId) {
                        peerId -> conversation.copy(connected = false)
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
                peerId = message.peerId,
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
        session.requestedEndpointId = null
        session.nearbyTransport.stop()
        session.connectedPeers.clear()
        session.state.update {
            it.copy(
                nearbyActive = false,
                screen = if (it.screen == ChatScreen.CONNECTING) ChatScreen.CHATS else it.screen,
                discoveredDevices = emptyList(),
                authenticationDigits = null,
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
