package com.example.blap.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class LocalChatPersistence(private val session: MessagingSession) {

    fun canonicalPeerId(peerId: String): String {
        session.connectedPeers[peerId]?.takeIf { it.accountVerified && it.accountUid.isNotBlank() }?.let {
            return "account:${it.accountUid}"
        }
        val contacts = session.store.getSavedContacts()
        contacts.filter { peerId in ContactIdentity.aliases(it) }
            .distinctBy { ContactIdentity.conversationId(it) }.singleOrNull()?.let {
            return ContactIdentity.conversationId(it) ?: peerId
        }
        return session.store.getConversations().firstOrNull { conversation ->
            conversation.peerId.startsWith("account:") && session.store.getMessages(conversation.peerId).any {
                it.senderId == peerId && it.senderAccountId == conversation.peerId.removePrefix("account:")
            }
        }?.peerId ?: peerId
    }

    fun transportPeerId(peerId: String): String {
        if (!peerId.startsWith("account:")) return peerId
        session.connectedPeers.values.firstOrNull {
            it.accountVerified && it.accountUid == peerId.removePrefix("account:")
        }?.let { return it.peerId }
        val paired = session.store.getSavedContacts().firstOrNull {
            ContactIdentity.conversationId(it) == peerId
        }?.linkedPeerId
        val senders = session.store.getMessages(peerId).filter {
            it.author == MessageAuthor.PEER && it.senderId.isNotBlank()
        }.map { it.senderId }.distinct().reversed()
        val candidates = (listOfNotNull(paired) + senders).filter {
            val connected = session.connectedPeers[it]
            connected == null || !connected.accountVerified || connected.accountUid == peerId.removePrefix("account:")
        }
        return candidates.firstOrNull { session.connectedPeers.containsKey(it) }
            ?: candidates.firstOrNull() ?: peerId
    }

    fun mergeContactConversations(contact: SavedContact, extraAliases: Set<String> = emptySet()) {
        val canonical = ContactIdentity.conversationId(contact) ?: return
        val otherContacts = session.store.getSavedContacts()
        val aliases = (ContactIdentity.aliases(contact) + extraAliases).filter { alias ->
            alias == canonical || otherContacts.none {
                it.id != contact.id && it.cloudUserId.isNotBlank() && it.cloudUserId != contact.cloudUserId &&
                    alias in ContactIdentity.aliases(it)
            }
        }.toSet()
        if (session.store.getConversations().any { it.peerId in aliases }) {
            session.store.savePeer(canonical, contact.name, contact.phoneHash)
            if (contact.username.isNotBlank()) session.store.savePeerUsername(canonical, contact.username)
        }
        aliases.filter { it != canonical }.forEach { mergeConversationAlias(it, canonical) }
    }

    fun mergeConversationAlias(alias: String, canonical: String) {
        if (alias == canonical) return
        session.store.moveConversation(alias, canonical)
        session.state.update { state ->
            val draft = state.messageDrafts[canonical] ?: state.messageDrafts[alias]
            state.copy(selectedPeerId = if (state.selectedPeerId == alias) canonical else state.selectedPeerId,
                messageDrafts = (state.messageDrafts - alias).let {
                    if (draft == null) it else it + (canonical to draft)
                })
        }
    }

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
        val canonical = canonicalPeerId(peerId)
        session.store.moveConversation(oldId, canonical)
        session.state.update { state ->
            if (state.selectedPeerId == oldId) state.copy(selectedPeerId = canonical) else state
        }
        if (session.state.value.selectedPeerId == canonical) reloadMessagesNow(canonical)
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
        contacts.filter { it.cloudUserId.isNotBlank() }.forEach { mergeContactConversations(it) }
        val contactNames = contacts.mapNotNull { contact ->
            val id = ContactIdentity.conversationId(contact)
            id?.let { it to contact.name }
        }.toMap()
        val onlinePeerIds = contacts.filter { it.cloudUserId.isNotBlank() }.mapNotNull { contact ->
            ContactIdentity.conversationId(contact)
        }.toSet()
        val conversations = session.store.getConversations().filterNot {
            it.peerId == MeshGroup.ID || it.type == ConversationType.OPEN_MESH
        }.map { conversation ->
            conversation.copy(
                lastMessage = ChatFeatures.preview(conversation.lastMessage),
                name = if (conversation.type == ConversationType.DIRECT) {
                    contactNames[conversation.peerId] ?: conversation.name
                } else conversation.name,
                connected = if (conversation.type != ConversationType.DIRECT) session.connectedPeers.isNotEmpty()
                else session.connectedPeers.containsKey(transportPeerId(conversation.peerId)),
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
