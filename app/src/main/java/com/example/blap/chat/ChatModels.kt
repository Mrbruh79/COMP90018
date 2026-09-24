package com.example.blap.chat

enum class ChatScreen {
    WELCOME,
    CHATS,
    CONNECTING,
    CONVERSATION,
    CREATING_GROUP,
    MANAGING_CONTACTS,
    EDITING_CONTACT,
    EDITING_PROFILE,
    SHOWING_MY_CARD,
    GROUP_SETTINGS,
    SETTINGS,
    ERROR,
}

enum class ContactSource {
    MANUAL,
    DEVICE,
    QR,
}

data class ContactProfile(
    val displayName: String = "",
    val phoneNumber: String = "",
    val email: String = "",
    val bio: String = "",
    val websiteUrl: String = "",
    val instagramUrl: String = "",
    val xUrl: String = "",
    val linkedinUrl: String = "",
    val githubUrl: String = "",
)

enum class ConversationType {
    DIRECT,
    OPEN_MESH,
    PRIVATE_GROUP,
}

data class NearbyDevice(
    val endpointId: String,
    val name: String,
)

data class ConnectedPeer(
    val peerId: String,
    val endpointId: String,
    val name: String,
    val phoneHash: String = "",
)

enum class MessageAuthor {
    ME,
    PEER,
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
}

data class ChatMessage(
    val id: String,
    val peerId: String,
    val text: String,
    val author: MessageAuthor,
    val sentAt: Long,
    val status: MessageStatus,
    val senderId: String = "",
    val senderName: String = "",
    val senderPhoneHash: String = "",
)

data class ConversationSummary(
    val peerId: String,
    val name: String,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0,
    val connected: Boolean = false,
    val type: ConversationType = ConversationType.DIRECT,
    val memberCount: Int = 0,
)

data class GroupMember(
    val peerId: String,
    val name: String,
    val phoneHash: String = "",
)

data class SavedContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val phoneHash: String,
    val linkedPeerId: String? = null,
    val email: String = "",
    val bio: String = "",
    val websiteUrl: String = "",
    val instagramUrl: String = "",
    val xUrl: String = "",
    val linkedinUrl: String = "",
    val githubUrl: String = "",
    val source: ContactSource = ContactSource.MANUAL,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class DeviceContact(
    val name: String,
    val phoneNumber: String,
)

data class PrivateGroup(
    val id: String,
    val name: String,
    val ownerId: String,
    val createdAt: Long,
    val members: List<GroupMember>,
)

data class GroupContact(
    val peerId: String,
    val name: String,
    val connected: Boolean,
    val phoneNumber: String = "",
    val phoneHash: String = "",
    val availableOnMesh: Boolean = false,
)

data class OutgoingNearbyMessage(
    val messageId: String,
    val peerId: String,
    val text: String,
    val sentAt: Long,
    val isGroup: Boolean = false,
)

data class IncomingNearbyMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderPhoneHash: String,
    val text: String,
    val sentAt: Long,
)

data class StoredGroupMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderPhoneHash: String,
    val text: String,
    val sentAt: Long,
)

data class ChatUiState(
    val displayName: String = "",
    val phoneNumber: String = "",
    val screen: ChatScreen = ChatScreen.WELCOME,
    val nearbyActive: Boolean = false,
    val profileDraft: ContactProfile? = null,
    val canEditGroup: Boolean = false,
    val messageDrafts: Map<String, String> = emptyMap(),
    val notice: String? = null,
    val venueStatus: String = "Find a nearby place using your location. Internet access is required.",
    val checkingVenue: Boolean = false,
    val discoveredDevices: List<NearbyDevice> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val selectedPeerId: String? = null,
    val authenticationDigits: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val directConnectionCount: Int = 0,
    val groupNameDraft: String = "",
    val groupContacts: List<GroupContact> = emptyList(),
    val selectedGroupMemberIds: Set<String> = emptySet(),
    val savedContacts: List<SavedContact> = emptyList(),
    val selectedContactId: String? = null,
    val contactNameDraft: String = "",
    val contactPhoneDraft: String = "",
    val contactEmailDraft: String = "",
    val contactBioDraft: String = "",
    val contactWebsiteDraft: String = "",
    val contactInstagramDraft: String = "",
    val contactXDraft: String = "",
    val contactLinkedinDraft: String = "",
    val contactGithubDraft: String = "",
    val contactSourceDraft: ContactSource = ContactSource.MANUAL,
    val profileEmail: String = "",
    val profileBio: String = "",
    val profileWebsite: String = "",
    val profileInstagram: String = "",
    val profileX: String = "",
    val profileLinkedin: String = "",
    val profileGithub: String = "",
    val conversationSearch: String = "",
    val contactSearch: String = "",
    val error: String? = null,
    val nameError: String? = null,
    val phoneError: String? = null,
)

object MeshGroup {
    const val ID = "__blap_mesh_group__"
    const val NAME = "Mesh group"
}
