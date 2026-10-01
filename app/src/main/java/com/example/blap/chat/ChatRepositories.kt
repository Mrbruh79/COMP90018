package com.example.blap.chat

/** Saved contacts and identities learned from direct or relayed peers. */
interface ContactRepository {
    fun savePeer(peerId: String, name: String, phoneHash: String = "")
    fun saveMeshPeer(peerId: String, name: String, phoneHash: String = "")
    fun saveContact(contact: SavedContact)
    fun getSavedContacts(): List<SavedContact>
    fun deleteContact(contactId: String) = Unit
    fun linkContact(phoneHash: String, peerId: String)
    fun getKnownContacts(): List<GroupMember>
}

interface GroupRepository {
    fun saveGroup(group: PrivateGroup)
    fun getGroups(): List<PrivateGroup>
    fun getCloudPendingGroups(): List<PrivateGroup> = emptyList()
    fun markGroupCloudSynced(groupId: String, revision: Long) = Unit
    fun deleteGroup(groupId: String) = Unit
    fun isGroupMember(groupId: String, peerId: String, phoneHash: String = ""): Boolean
}

/** Blocking local persistence. Callers keep database work off the UI thread. */
interface ChatRepository {
    fun saveMessage(message: ChatMessage): Boolean
    fun updateMessageStatus(messageId: String, status: MessageStatus)
    fun getConversations(): List<ConversationSummary>
    fun getMessages(peerId: String): List<ChatMessage>
    fun hasCloudPeerMessage(peerId: String): Boolean = getMessages(peerId).any {
        it.author == MessageAuthor.PEER && it.senderAccountId.isNotBlank()
    }
    fun getPendingMessages(peerId: String): List<ChatMessage>
    fun getCloudPendingMessages(): List<ChatMessage> = emptyList()
    fun markCloudSynced(messageId: String) = Unit
    fun moveConversation(fromPeerId: String, toPeerId: String) = Unit
}
