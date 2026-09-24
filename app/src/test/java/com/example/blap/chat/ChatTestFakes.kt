package com.example.blap.chat

internal class FakeIdentityStore : IdentityStore {
    val expectedPeerId = "local-peer"
    private var name = ""
    private var phoneNumber = "+15551234567"

    override fun getPeerId() = expectedPeerId
    override fun getDisplayName() = name
    override fun saveDisplayName(name: String) {
        this.name = name
    }

    override fun getPhoneNumber() = phoneNumber

    override fun savePhoneNumber(phoneNumber: String) {
        this.phoneNumber = phoneNumber
    }
}

internal class FakeChatStore : ChatStore {
    private val peers = linkedMapOf<String, String>()
    private val messages = linkedMapOf<String, ChatMessage>()
    private val groups = linkedMapOf<String, PrivateGroup>()
    private val contacts = linkedMapOf<String, SavedContact>()
    private val phoneHashes = linkedMapOf<String, String>()

    override fun savePeer(peerId: String, name: String, phoneHash: String) {
        peers[peerId] = name
        phoneHashes[peerId] = phoneHash
    }

    override fun saveMeshPeer(peerId: String, name: String, phoneHash: String) {
        peers[peerId] = name
        phoneHashes[peerId] = phoneHash
    }

    override fun saveContact(contact: SavedContact) {
        contacts.entries.removeAll { it.value.id == contact.id }
        contacts[contact.phoneHash] = contact
    }

    override fun getSavedContacts(): List<SavedContact> = contacts.values.toList()

    override fun deleteContact(contactId: String) {
        contacts.entries.removeAll { it.value.id == contactId }
    }

    override fun linkContact(phoneHash: String, peerId: String) {
        val contact = contacts[phoneHash] ?: return
        contacts[phoneHash] = contact.copy(linkedPeerId = peerId)
    }

    override fun getKnownContacts(): List<GroupMember> = peers
        .filterKeys { it != MeshGroup.ID }
        .map { GroupMember(it.key, it.value, phoneHashes[it.key].orEmpty()) }

    override fun saveGroup(group: PrivateGroup) {
        groups[group.id] = group
    }

    override fun getGroups(): List<PrivateGroup> = groups.values.toList()

    override fun isGroupMember(groupId: String, peerId: String, phoneHash: String): Boolean =
        groups[groupId]?.members?.any {
            it.peerId == peerId || (phoneHash.isNotBlank() && it.phoneHash == phoneHash)
        } == true

    override fun saveMessage(message: ChatMessage): Boolean {
        if (messages.containsKey(message.id)) return false
        messages[message.id] = message
        return true
    }

    override fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val message = messages[messageId] ?: return
        if (message.status.ordinal < status.ordinal) messages[messageId] = message.copy(status = status)
    }

    override fun getConversations(): List<ConversationSummary> {
        val direct = peers.map { (peerId, name) ->
            val last = messages.values.filter { it.peerId == peerId }.maxByOrNull { it.sentAt }
            ConversationSummary(
                peerId = peerId,
                name = name,
                lastMessage = last?.text.orEmpty(),
                lastMessageAt = last?.sentAt ?: 0L,
                type = if (peerId == MeshGroup.ID) ConversationType.OPEN_MESH else ConversationType.DIRECT,
            )
        }
        val privateGroups = groups.values.map { group ->
            val last = messages.values.filter { it.peerId == group.id }.maxByOrNull { it.sentAt }
            ConversationSummary(
                peerId = group.id,
                name = group.name,
                lastMessage = last?.text.orEmpty(),
                lastMessageAt = last?.sentAt ?: group.createdAt,
                type = ConversationType.PRIVATE_GROUP,
                memberCount = group.members.size,
            )
        }
        return direct + privateGroups
    }

    override fun getMessages(peerId: String): List<ChatMessage> =
        messages.values.filter { it.peerId == peerId }.sortedBy { it.sentAt }

    override fun getPendingMessages(peerId: String): List<ChatMessage> =
        getMessages(peerId).filter {
            it.author == MessageAuthor.ME && it.status != MessageStatus.DELIVERED
        }

    override fun close() = Unit
}

internal class FakeNearbyChatController : NearbyChatController {
    override var listener: NearbyChatController.Listener? = null
    var advertisingStarted = false
    var advertisedName: String? = null
    var advertisedPeerId: String? = null
    var discoveryStarted = false
    val sentMessages = mutableListOf<OutgoingMessageEnvelope>()
    val acknowledgements = mutableListOf<Pair<String, String>>()
    val groupSynchronizations = mutableListOf<Pair<String, List<StoredGroupMessage>>>()
    val publishedGroups = mutableListOf<PrivateGroup>()
    var stopped = false
    var startAdvertisingCalls = 0
    var closeCalls = 0

    override fun startAdvertising(displayName: String, peerId: String, phoneHash: String) {
        startAdvertisingCalls++
        advertisingStarted = true
        advertisedName = displayName
        advertisedPeerId = peerId
    }

    override fun startDiscovery() {
        discoveryStarted = true
    }

    override fun connectToDevice(endpointId: String) = Unit
    override fun sendMessage(message: OutgoingMessageEnvelope) {
        sentMessages += message
    }

    override fun publishGroup(group: PrivateGroup) {
        publishedGroups += group
    }

    override fun synchronizeGroups(
        peerId: String,
        groups: List<PrivateGroup>,
        messages: List<StoredGroupMessage>,
    ) {
        groupSynchronizations += peerId to messages
    }

    override fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String) {
        acknowledgements += senderId to messageId
    }
    override fun disconnect(peerId: String) = Unit
    override fun stop() {
        stopped = true
    }
    override fun close() {
        closeCalls++
    }
}
