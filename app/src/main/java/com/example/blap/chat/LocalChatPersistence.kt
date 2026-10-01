package com.example.blap.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class LocalChatPersistence(private val session: MessagingSession) {

    fun notifyIncoming(message: ChatMessage) {
        val state = session.state.value
        val conversation = state.conversations.firstOrNull { it.peerId == message.peerId }
        session.dependencies.notifier.incoming(
            message,
            conversation?.name ?: message.senderName.ifBlank { "New chat" },
            conversation?.type ?: if (message.peerId == MeshGroup.ID) ConversationType.OPEN_MESH
                else if (session.store.getGroups().any { it.id == message.peerId }) ConversationType.PRIVATE_GROUP
                else ConversationType.DIRECT,
            state.screen == ChatScreen.CONVERSATION && state.selectedPeerId == message.peerId,
        )
    }

    fun movePhoneConversationToPeer(phoneHash: String, peerId: String) {
        val oldId = "phone:$phoneHash"
        session.store.moveConversation(oldId, peerId)
        session.state.update { state ->
            if (state.selectedPeerId == oldId) state.copy(selectedPeerId = peerId) else state
        }
        if (session.state.value.selectedPeerId == peerId) reloadMessagesNow(peerId)
    }

    fun setMessageStatus(peerId: String, messageId: String, status: MessageStatus) {
        session.state.update { state ->
            if (state.selectedPeerId != peerId) return@update state
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == messageId && message.status.ordinal < status.ordinal) {
                        message.copy(status = status)
                    } else {
                        message
                    }
                },
            )
        }
        session.workScope.launch { session.store.updateMessageStatus(messageId, status) }
    }

    fun reloadConversations() {
        session.workScope.launch { reloadConversationsNow() }
    }

    fun reloadConversationsNow() {
        val contacts = session.store.getSavedContacts()
        val contactNames = contacts.mapNotNull { contact ->
            val id = contact.linkedPeerId ?: ContactIdentity.localPeerId(contact.phoneHash, contact.email,
                contact.googleAccountEmail) ?: contact.cloudUserId.takeIf(String::isNotBlank)
                ?.let { "account:$it" }
            id?.let { it to contact.name }
        }.toMap()
        val onlinePeerIds = contacts.filter { it.cloudUserId.isNotBlank() }.mapNotNull { contact ->
            contact.linkedPeerId ?: ContactIdentity.localPeerId(
                contact.phoneHash, contact.email, contact.googleAccountEmail,
            ) ?: "account:${contact.cloudUserId}"
        }.toSet()
        val conversations = session.store.getConversations().map { conversation ->
            conversation.copy(
                lastMessage = ChatFeatures.preview(conversation.lastMessage),
                name = if (conversation.type == ConversationType.DIRECT) {
                    contactNames[conversation.peerId] ?: conversation.name
                } else conversation.name,
                connected = if (conversation.type != ConversationType.DIRECT) session.connectedPeers.isNotEmpty()
                else session.connectedPeers.containsKey(conversation.peerId),
                onlineAccountLinked = conversation.type == ConversationType.DIRECT &&
                    (conversation.peerId in onlinePeerIds || session.store.hasCloudPeerMessage(conversation.peerId)),
            )
        }.sortedByDescending { if (it.lastMessage.isBlank()) 0L else it.lastMessageAt }
        session.state.update {
            it.copy(
                conversations = conversations,
                directConnectionCount = session.connectedPeers.size,
            )
        }
    }

    fun reloadMessages(peerId: String) {
        session.workScope.launch { reloadMessagesNow(peerId) }
    }

    fun reloadMessagesNow(peerId: String) {
        val messages = session.store.getMessages(peerId)
        session.state.update { state ->
            if (state.selectedPeerId == peerId) state.copy(messages = messages) else state
        }
    }

    fun reloadSavedContactsNow() {
        val contacts = session.store.getSavedContacts()
        session.state.update { state -> state.copy(savedContacts = contacts) }
    }

}
