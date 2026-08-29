package com.example.blap.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val nearbyChatController: NearbyChatController,
    private val chatStore: ChatStore,
    private val identityStore: IdentityStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(), NearbyChatController.Listener {
    private val workScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectedPeers = ConcurrentHashMap<String, ConnectedPeer>()
    private val localPeerId = identityStore.getPeerId()
    private var localPhoneHash = PhoneIdentity.hash(identityStore.getPhoneNumber()).orEmpty()
    private val initialProfile = identityStore.getProfile()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            displayName = initialProfile.displayName,
            phoneNumber = initialProfile.phoneNumber,
            profileEmail = initialProfile.email,
            profileBio = initialProfile.bio,
            profileWebsite = initialProfile.websiteUrl,
            profileInstagram = initialProfile.instagramUrl,
            profileX = initialProfile.xUrl,
            profileLinkedin = initialProfile.linkedinUrl,
            profileGithub = initialProfile.githubUrl,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        nearbyChatController.listener = this
        workScope.launch {
            chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
            reloadConversationsNow()
            reloadSavedContactsNow()
        }
    }

    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name.take(MAX_NAME_LENGTH)) }
    }

    fun updatePhoneNumber(phoneNumber: String) {
        _uiState.update { it.copy(phoneNumber = phoneNumber.take(MAX_PHONE_LENGTH)) }
    }

    fun startChat() {
        val name = _uiState.value.displayName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Enter a display name first.") }
            return
        }
        val normalizedPhone = PhoneIdentity.normalize(_uiState.value.phoneNumber)
        if (normalizedPhone == null) {
            _uiState.update { it.copy(error = "Enter a valid phone number, including country code.") }
            return
        }

        identityStore.saveProfile(currentProfile().copy(displayName = name, phoneNumber = normalizedPhone))
        localPhoneHash = requireNotNull(PhoneIdentity.hash(normalizedPhone))
        _uiState.update {
            it.copy(
                displayName = name,
                phoneNumber = normalizedPhone,
                connectionState = ChatConnectionState.DISCOVERING,
                selectedPeerId = null,
                authenticationDigits = null,
                messages = emptyList(),
                error = null,
            )
        }
        nearbyChatController.startAdvertising(name, localPeerId, localPhoneHash)
        nearbyChatController.startDiscovery()
    }

    fun connectToDevice(endpointId: String) {
        val device = _uiState.value.discoveredDevices.firstOrNull { it.endpointId == endpointId }
            ?: return
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.CONNECTING,
                authenticationDigits = null,
                error = null,
            )
        }
        nearbyChatController.connectToDevice(device.endpointId)
    }

    fun openConversation(peerId: String) {
        if (_uiState.value.conversations.none { it.peerId == peerId }) return
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.CONNECTED,
                selectedPeerId = peerId,
                messages = emptyList(),
                error = null,
            )
        }
        reloadMessages(peerId)
    }

    fun showConversationList() {
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.DISCOVERING,
                selectedPeerId = null,
                authenticationDigits = null,
                messages = emptyList(),
                groupNameDraft = "",
                groupContacts = emptyList(),
                selectedGroupMemberIds = emptySet(),
            )
        }
    }

    fun beginManageContacts() {
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.MANAGING_CONTACTS,
                selectedContactId = null,
                contactNameDraft = "",
                contactPhoneDraft = "",
                error = null,
            )
        }
        workScope.launch { reloadSavedContactsNow() }
    }

    fun beginAddContact() {
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.EDITING_CONTACT,
                selectedContactId = null,
                contactNameDraft = "",
                contactPhoneDraft = "",
                contactEmailDraft = "",
                contactBioDraft = "",
                contactWebsiteDraft = "",
                contactInstagramDraft = "",
                contactXDraft = "",
                contactLinkedinDraft = "",
                contactGithubDraft = "",
                contactSourceDraft = ContactSource.MANUAL,
                error = null,
            )
        }
    }

    fun openContact(contactId: String) {
        val contact = _uiState.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.EDITING_CONTACT,
                selectedContactId = contact.id,
                contactNameDraft = contact.name,
                contactPhoneDraft = contact.phoneNumber,
                contactEmailDraft = contact.email,
                contactBioDraft = contact.bio,
                contactWebsiteDraft = contact.websiteUrl,
                contactInstagramDraft = contact.instagramUrl,
                contactXDraft = contact.xUrl,
                contactLinkedinDraft = contact.linkedinUrl,
                contactGithubDraft = contact.githubUrl,
                contactSourceDraft = contact.source,
                error = null,
            )
        }
    }

    fun updateContactDraft(profile: ContactProfile) {
        _uiState.update {
            it.copy(
                contactNameDraft = profile.displayName.take(MAX_NAME_LENGTH),
                contactPhoneDraft = profile.phoneNumber.take(MAX_PHONE_LENGTH),
                contactEmailDraft = profile.email.take(MAX_EMAIL_LENGTH),
                contactBioDraft = profile.bio.take(MAX_BIO_LENGTH),
                contactWebsiteDraft = profile.websiteUrl.take(MAX_URL_LENGTH),
                contactInstagramDraft = profile.instagramUrl.take(MAX_URL_LENGTH),
                contactXDraft = profile.xUrl.take(MAX_URL_LENGTH),
                contactLinkedinDraft = profile.linkedinUrl.take(MAX_URL_LENGTH),
                contactGithubDraft = profile.githubUrl.take(MAX_URL_LENGTH),
            )
        }
    }

    fun importScannedContactCard(payload: String) {
        val profile = ContactCardCodec.decode(payload)
        if (profile == null) {
            showError("That QR code is not a BLAP contact card.")
            return
        }
        beginAddContact()
        updateContactDraft(profile)
        _uiState.update { it.copy(contactSourceDraft = ContactSource.QR) }
    }

    fun updateContactName(name: String) {
        _uiState.update { it.copy(contactNameDraft = name.take(MAX_NAME_LENGTH)) }
    }

    fun updateContactPhone(phoneNumber: String) {
        _uiState.update { it.copy(contactPhoneDraft = phoneNumber.take(MAX_PHONE_LENGTH)) }
    }

    fun saveContact() {
        val state = _uiState.value
        val name = state.contactNameDraft.trim()
        val normalizedPhone = PhoneIdentity.normalize(state.contactPhoneDraft)
        val phoneHash = PhoneIdentity.hash(state.contactPhoneDraft)
        if (name.isBlank() || normalizedPhone == null || phoneHash == null) {
            _uiState.update { it.copy(error = "Enter a contact name and valid phone number.") }
            return
        }
        workScope.launch {
            val linkedPeerId = chatStore.getKnownContacts()
                .firstOrNull { it.phoneHash == phoneHash }
                ?.peerId
            val existing = state.selectedContactId?.let { id ->
                state.savedContacts.firstOrNull { it.id == id }
            }
            chatStore.saveContact(
                SavedContact(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    phoneNumber = normalizedPhone,
                    phoneHash = phoneHash,
                    linkedPeerId = existing?.linkedPeerId ?: linkedPeerId,
                    email = state.contactEmailDraft.trim(),
                    bio = state.contactBioDraft.trim(),
                    websiteUrl = state.contactWebsiteDraft.trim(),
                    instagramUrl = state.contactInstagramDraft.trim(),
                    xUrl = state.contactXDraft.trim(),
                    linkedinUrl = state.contactLinkedinDraft.trim(),
                    githubUrl = state.contactGithubDraft.trim(),
                    source = state.contactSourceDraft,
                ),
            )
            reloadSavedContactsNow()
            _uiState.update {
                it.copy(
                    connectionState = ChatConnectionState.MANAGING_CONTACTS,
                    selectedContactId = null,
                    contactNameDraft = "",
                    contactPhoneDraft = "",
                )
            }
        }
    }

    fun deleteContact() {
        val contactId = _uiState.value.selectedContactId ?: return
        workScope.launch {
            chatStore.deleteContact(contactId)
            reloadSavedContactsNow()
            _uiState.update {
                it.copy(connectionState = ChatConnectionState.MANAGING_CONTACTS, selectedContactId = null)
            }
        }
    }

    fun importDeviceContacts(contacts: List<DeviceContact>) {
        workScope.launch {
            val meshByHash = chatStore.getKnownContacts()
                .filter { it.phoneHash.isNotBlank() }
                .associateBy(GroupMember::phoneHash)
            val existingHashes = chatStore.getSavedContacts().map(SavedContact::phoneHash).toMutableSet()
            contacts.forEach { contact ->
                val normalized = PhoneIdentity.normalize(contact.phoneNumber) ?: return@forEach
                val hash = PhoneIdentity.hash(normalized) ?: return@forEach
                if (hash in existingHashes) return@forEach
                chatStore.saveContact(
                    SavedContact(
                        id = UUID.randomUUID().toString(),
                        name = contact.name.take(MAX_NAME_LENGTH),
                        phoneNumber = normalized,
                        phoneHash = hash,
                        linkedPeerId = meshByHash[hash]?.peerId,
                        source = ContactSource.DEVICE,
                    ),
                )
                existingHashes += hash
            }
            reloadSavedContactsNow()
        }
    }

    fun beginCreateGroup() {
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.CREATING_GROUP,
                groupNameDraft = "",
                selectedGroupMemberIds = emptySet(),
                error = null,
            )
        }
        workScope.launch { reloadGroupContactsNow() }
    }

    fun beginGroupSettings() {
        val groupId = _uiState.value.selectedPeerId ?: return
        workScope.launch {
            val group = chatStore.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            reloadGroupContactsNow(force = true)
            _uiState.update {
                it.copy(
                    connectionState = ChatConnectionState.GROUP_SETTINGS,
                    groupNameDraft = group.name,
                    selectedGroupMemberIds = group.members
                        .filterNot { member -> member.peerId == localPeerId }
                        .map(GroupMember::peerId)
                        .toSet(),
                    error = null,
                )
            }
        }
    }

    fun saveGroupSettings() {
        val state = _uiState.value
        val groupId = state.selectedPeerId ?: return
        workScope.launch {
            val current = chatStore.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            if (current.ownerId != localPeerId) {
                showError("Only the group owner can change members or the group name.")
                return@launch
            }
            val name = state.groupNameDraft.trim()
            if (name.isBlank()) {
                showError("Enter a group name.")
                return@launch
            }
            val selected = state.groupContacts.filter { it.peerId in state.selectedGroupMemberIds }
            val group = current.copy(
                name = name,
                createdAt = System.currentTimeMillis(),
                members = listOf(GroupMember(localPeerId, state.displayName, localPhoneHash)) +
                    selected.map { GroupMember(it.peerId, it.name, it.phoneHash) },
            )
            chatStore.saveGroup(group)
            nearbyChatController.publishGroup(group)
            reloadConversationsNow()
            _uiState.update { it.copy(connectionState = ChatConnectionState.CONNECTED) }
        }
    }

    fun deleteCurrentGroup() {
        val groupId = _uiState.value.selectedPeerId ?: return
        workScope.launch {
            val group = chatStore.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            if (group.ownerId != localPeerId) {
                showError("Only the group owner can delete this group from the mesh.")
                return@launch
            }
            chatStore.deleteGroup(groupId)
            reloadConversationsNow()
            showConversationList()
        }
    }

    fun showMyCard() {
        _uiState.update { it.copy(connectionState = ChatConnectionState.SHOWING_MY_CARD, error = null) }
    }

    fun editProfile() {
        _uiState.update { it.copy(connectionState = ChatConnectionState.EDITING_PROFILE, error = null) }
    }

    fun updateProfile(profile: ContactProfile) {
        _uiState.update {
            it.copy(
                displayName = profile.displayName.take(MAX_NAME_LENGTH),
                phoneNumber = profile.phoneNumber.take(MAX_PHONE_LENGTH),
                profileEmail = profile.email.take(MAX_EMAIL_LENGTH),
                profileBio = profile.bio.take(MAX_BIO_LENGTH),
                profileWebsite = profile.websiteUrl.take(MAX_URL_LENGTH),
                profileInstagram = profile.instagramUrl.take(MAX_URL_LENGTH),
                profileX = profile.xUrl.take(MAX_URL_LENGTH),
                profileLinkedin = profile.linkedinUrl.take(MAX_URL_LENGTH),
                profileGithub = profile.githubUrl.take(MAX_URL_LENGTH),
            )
        }
    }

    fun saveProfile() {
        val wasActive = _uiState.value.connectionState != ChatConnectionState.IDLE &&
            _uiState.value.connectionState != ChatConnectionState.ERROR
        val profile = currentProfile()
        val normalizedPhone = PhoneIdentity.normalize(profile.phoneNumber)
        if (profile.displayName.trim().isBlank() || normalizedPhone == null) {
            showError("Enter your name and a valid phone number, including country code.")
            return
        }
        val saved = profile.copy(
            displayName = profile.displayName.trim(),
            phoneNumber = normalizedPhone,
            email = profile.email.trim(),
            bio = profile.bio.trim(),
        )
        identityStore.saveProfile(saved)
        localPhoneHash = PhoneIdentity.hash(normalizedPhone).orEmpty()
        updateProfile(saved)
        if (wasActive) {
            nearbyChatController.stop()
            connectedPeers.clear()
            _uiState.update {
                it.copy(
                    discoveredDevices = emptyList(),
                    directConnectionCount = 0,
                    conversations = it.conversations.map { conversation ->
                        conversation.copy(connected = false)
                    },
                )
            }
            nearbyChatController.startAdvertising(saved.displayName, localPeerId, localPhoneHash)
            nearbyChatController.startDiscovery()
        }
        showMyCard()
    }

    fun showSettings() {
        _uiState.update { it.copy(connectionState = ChatConnectionState.SETTINGS, error = null) }
    }

    fun updateConversationSearch(query: String) {
        _uiState.update { it.copy(conversationSearch = query.take(80)) }
    }

    fun updateContactSearch(query: String) {
        _uiState.update { it.copy(contactSearch = query.take(80)) }
    }

    fun updateGroupName(name: String) {
        _uiState.update { it.copy(groupNameDraft = name.take(MAX_GROUP_NAME_LENGTH)) }
    }

    fun toggleGroupMember(peerId: String) {
        if (_uiState.value.groupContacts.none { it.peerId == peerId }) return
        _uiState.update { state ->
            val selected = state.selectedGroupMemberIds.toMutableSet()
            if (!selected.add(peerId)) selected.remove(peerId)
            state.copy(selectedGroupMemberIds = selected)
        }
    }

    fun createPrivateGroup() {
        val state = _uiState.value
        val name = state.groupNameDraft.trim()
        val selectedContacts = state.groupContacts.filter {
            it.peerId in state.selectedGroupMemberIds
        }
        if (name.isBlank() || selectedContacts.isEmpty()) {
            _uiState.update { it.copy(error = "Enter a group name and choose at least one contact.") }
            return
        }

        val group = PrivateGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            ownerId = localPeerId,
            createdAt = System.currentTimeMillis(),
            members = listOf(GroupMember(localPeerId, state.displayName, localPhoneHash)) + selectedContacts.map {
                GroupMember(it.peerId, it.name, it.phoneHash)
            },
        )
        workScope.launch {
            chatStore.saveGroup(group)
            nearbyChatController.publishGroup(group)
            reloadConversationsNow()
            _uiState.update {
                it.copy(
                    connectionState = ChatConnectionState.CONNECTED,
                    selectedPeerId = group.id,
                    messages = emptyList(),
                    groupNameDraft = "",
                    groupContacts = emptyList(),
                    selectedGroupMemberIds = emptySet(),
                )
            }
        }
    }

    fun handleBack() {
        when (_uiState.value.connectionState) {
            ChatConnectionState.CONNECTED,
            ChatConnectionState.CONNECTING,
            ChatConnectionState.CREATING_GROUP,
            -> showConversationList()

            ChatConnectionState.MANAGING_CONTACTS,
            ChatConnectionState.SHOWING_MY_CARD,
            ChatConnectionState.SETTINGS,
            -> showConversationList()

            ChatConnectionState.EDITING_CONTACT -> beginManageContacts()
            ChatConnectionState.EDITING_PROFILE -> showMyCard()
            ChatConnectionState.GROUP_SETTINGS -> {
                _uiState.update { it.copy(connectionState = ChatConnectionState.CONNECTED) }
            }

            ChatConnectionState.DISCOVERING,
            ChatConnectionState.ERROR,
            -> stopChat()

            ChatConnectionState.IDLE -> Unit
        }
    }

    fun sendMessage(text: String) {
        val peerId = _uiState.value.selectedPeerId ?: return
        val conversation = _uiState.value.conversations.firstOrNull { it.peerId == peerId } ?: return
        val cleanText = text.trim().take(MAX_MESSAGE_LENGTH)
        if (cleanText.isBlank()) return

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            peerId = peerId,
            text = cleanText,
            author = MessageAuthor.ME,
            sentAt = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
            senderId = localPeerId,
            senderName = _uiState.value.displayName,
            senderPhoneHash = localPhoneHash,
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + message,
                conversations = updateConversationPreview(state.conversations, message),
            )
        }

        workScope.launch {
            chatStore.saveMessage(message)
            reloadConversationsNow()
            if (conversation.type != ConversationType.DIRECT && connectedPeers.isNotEmpty()) {
                sendStoredMessage(message)
            }
            else if (connectedPeers.containsKey(peerId)) sendStoredMessage(message)
        }
    }

    fun disconnect(peerId: String) {
        nearbyChatController.disconnect(peerId)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    override fun onDeviceFound(device: NearbyDevice) {
        _uiState.update { state ->
            val devices = state.discoveredDevices
                .filterNot { it.endpointId == device.endpointId }
                .plus(device)
                .sortedBy { it.name.lowercase() }
            state.copy(discoveredDevices = devices)
        }
    }

    override fun onDeviceLost(endpointId: String) {
        _uiState.update { state ->
            state.copy(
                discoveredDevices = state.discoveredDevices.filterNot { it.endpointId == endpointId },
            )
        }
    }

    override fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String) {
        _uiState.update { state ->
            if (state.connectionState == ChatConnectionState.CONNECTED) {
                state.copy(authenticationDigits = authenticationDigits)
            } else {
                state.copy(
                    connectionState = ChatConnectionState.CONNECTING,
                    authenticationDigits = authenticationDigits,
                    error = null,
                )
            }
        }
    }

    override fun onConnected(peer: ConnectedPeer) {
        connectedPeers[peer.peerId] = peer
        val shouldOpen = _uiState.value.connectionState == ChatConnectionState.CONNECTING

        _uiState.update { state ->
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
                connectionState = if (shouldOpen) ChatConnectionState.CONNECTED else state.connectionState,
                selectedPeerId = if (shouldOpen) peer.peerId else state.selectedPeerId,
                discoveredDevices = state.discoveredDevices.filterNot { it.endpointId == peer.endpointId },
                conversations = conversations,
                directConnectionCount = connectedPeers.size,
                authenticationDigits = null,
                error = null,
            )
        }

        workScope.launch {
            chatStore.savePeer(peer.peerId, peer.name, peer.phoneHash)
            chatStore.linkContact(peer.phoneHash, peer.peerId)
            reloadConversationsNow()
            if (_uiState.value.selectedPeerId == peer.peerId) reloadMessagesNow(peer.peerId)
            chatStore.getPendingMessages(peer.peerId).forEach(::sendStoredMessage)
            synchronizeGroupsWith(peer.peerId)
        }
    }

    override fun onGroupReceived(group: PrivateGroup) {
        if (group.members.none { it.peerId == localPeerId || it.phoneHash == localPhoneHash }) return
        workScope.launch {
            chatStore.saveGroup(group)
            reloadConversationsNow()
        }
    }

    override fun onMeshPeerFound(peer: GroupMember) {
        if (peer.peerId == localPeerId) return
        workScope.launch {
            chatStore.saveMeshPeer(peer.peerId, peer.name, peer.phoneHash)
            chatStore.linkContact(peer.phoneHash, peer.peerId)
            if (_uiState.value.connectionState == ChatConnectionState.CREATING_GROUP) {
                reloadGroupContactsNow()
            } else if (_uiState.value.connectionState == ChatConnectionState.MANAGING_CONTACTS) {
                reloadSavedContactsNow()
            }
        }
    }

    override fun onMessageReceived(message: IncomingNearbyMessage) {
        val savedMessage = ChatMessage(
            id = message.messageId,
            peerId = message.conversationId,
            text = message.text,
            author = if (message.senderId == localPeerId) MessageAuthor.ME else MessageAuthor.PEER,
            sentAt = message.sentAt,
            status = MessageStatus.DELIVERED,
            senderId = message.senderId,
            senderName = message.senderName,
            senderPhoneHash = message.senderPhoneHash,
        )

        workScope.launch {
            val isOpenMesh = message.conversationId == MeshGroup.ID
            val isDirect = message.conversationId == message.senderId
            val isPrivateMember = !isOpenMesh && !isDirect &&
                chatStore.isGroupMember(message.conversationId, localPeerId, localPhoneHash) &&
                chatStore.isGroupMember(
                    message.conversationId,
                    message.senderId,
                    message.senderPhoneHash,
                )
            if (!isOpenMesh && !isDirect && !isPrivateMember) return@launch

            if (isOpenMesh) {
                chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
            } else if (isDirect) {
                chatStore.savePeer(message.senderId, message.senderName, message.senderPhoneHash)
            }
            val inserted = chatStore.saveMessage(savedMessage)
            if (inserted) {
                reloadConversationsNow()
                if (_uiState.value.selectedPeerId == message.conversationId) {
                    reloadMessagesNow(message.conversationId)
                }
            }
            nearbyChatController.acknowledgeMessage(
                message.conversationId,
                message.senderId,
                message.messageId,
            )
        }
    }

    override fun onMessageSent(peerId: String, messageId: String) {
        setMessageStatus(peerId, messageId, MessageStatus.SENT)
    }

    override fun onMessageDelivered(peerId: String, messageId: String) {
        setMessageStatus(peerId, messageId, MessageStatus.DELIVERED)
    }

    override fun onDisconnected(peerId: String) {
        connectedPeers.remove(peerId)
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    when (conversation.peerId) {
                        peerId -> conversation.copy(connected = false)
                        else -> if (conversation.type != ConversationType.DIRECT) {
                            conversation.copy(connected = connectedPeers.isNotEmpty())
                        } else {
                            conversation
                        }
                    }
                },
                directConnectionCount = connectedPeers.size,
            )
        }
    }

    override fun onError(message: String) {
        _uiState.update { state ->
            val nextState = if (state.connectionState == ChatConnectionState.IDLE) {
                ChatConnectionState.ERROR
            } else {
                state.connectionState
            }
            state.copy(connectionState = nextState, authenticationDigits = null, error = message)
        }
    }

    override fun onCleared() {
        nearbyChatController.close()
        workScope.cancel()
        chatStore.close()
    }

    private fun setMessageStatus(peerId: String, messageId: String, status: MessageStatus) {
        _uiState.update { state ->
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
        workScope.launch { chatStore.updateMessageStatus(messageId, status) }
    }

    private fun sendStoredMessage(message: ChatMessage) {
        nearbyChatController.sendMessage(
            OutgoingNearbyMessage(
                messageId = message.id,
                peerId = message.peerId,
                text = message.text,
                sentAt = message.sentAt,
                isGroup = _uiState.value.conversations.firstOrNull {
                    it.peerId == message.peerId
                }?.type != ConversationType.DIRECT,
            ),
        )
    }

    private fun reloadConversations() {
        workScope.launch { reloadConversationsNow() }
    }

    private fun reloadConversationsNow() {
        val conversations = chatStore.getConversations().map { conversation ->
            conversation.copy(
                connected = if (conversation.type != ConversationType.DIRECT) connectedPeers.isNotEmpty()
                else connectedPeers.containsKey(conversation.peerId),
            )
        }
        _uiState.update {
            it.copy(
                conversations = conversations,
                directConnectionCount = connectedPeers.size,
            )
        }
    }

    private fun reloadMessages(peerId: String) {
        workScope.launch { reloadMessagesNow(peerId) }
    }

    private fun reloadMessagesNow(peerId: String) {
        val messages = chatStore.getMessages(peerId)
        _uiState.update { state ->
            if (state.selectedPeerId == peerId) state.copy(messages = messages) else state
        }
    }

    private fun updateConversationPreview(
        conversations: List<ConversationSummary>,
        message: ChatMessage,
    ): List<ConversationSummary> {
        val oldConversation = conversations.firstOrNull { it.peerId == message.peerId } ?: return conversations
        val updated = oldConversation.copy(
            lastMessage = message.text,
            lastMessageAt = message.sentAt,
        )
        return conversations.filterNot { it.peerId == message.peerId }.plus(updated)
            .sortedByDescending { it.lastMessageAt }
    }

    private fun synchronizeGroupsWith(peerId: String) {
        val groups = chatStore.getGroups()
        val conversationIds = listOf(MeshGroup.ID) + groups.map(PrivateGroup::id)
        val messages = conversationIds.flatMap { conversationId ->
            chatStore.getMessages(conversationId).map { message ->
                StoredGroupMessage(
                    messageId = message.id,
                    conversationId = conversationId,
                    senderId = message.senderId,
                    senderName = message.senderName,
                    senderPhoneHash = message.senderPhoneHash,
                    text = message.text,
                    sentAt = message.sentAt,
                )
            }
        }
        nearbyChatController.synchronizeGroups(peerId, groups, messages)
    }

    private fun reloadGroupContactsNow(force: Boolean = false) {
        val knownContacts = chatStore.getKnownContacts().associateBy(GroupMember::peerId).toMutableMap()
        connectedPeers.values.forEach { peer ->
            knownContacts[peer.peerId] = GroupMember(peer.peerId, peer.name, peer.phoneHash)
        }
        val contactsByHash = linkedMapOf<String, GroupContact>()
        knownContacts.values.forEach { contact ->
            val key = contact.phoneHash.ifBlank { "peer:${contact.peerId}" }
            contactsByHash[key] = GroupContact(
                peerId = contact.peerId,
                name = contact.name,
                connected = connectedPeers.containsKey(contact.peerId),
                phoneHash = contact.phoneHash,
                availableOnMesh = true,
            )
        }
        chatStore.getSavedContacts().forEach { contact ->
            val linkedPeerId = contact.linkedPeerId
                ?: knownContacts.values.firstOrNull { it.phoneHash == contact.phoneHash }?.peerId
            contactsByHash[contact.phoneHash] = GroupContact(
                peerId = linkedPeerId ?: "phone:${contact.phoneHash}",
                name = contact.name,
                connected = linkedPeerId?.let(connectedPeers::containsKey) == true,
                phoneNumber = contact.phoneNumber,
                phoneHash = contact.phoneHash,
                availableOnMesh = linkedPeerId != null,
            )
        }
        val contacts = contactsByHash.values.map { contact ->
            GroupContact(
                peerId = contact.peerId,
                name = contact.name,
                connected = contact.connected,
                phoneNumber = contact.phoneNumber,
                phoneHash = contact.phoneHash,
                availableOnMesh = contact.availableOnMesh,
            )
        }.sortedBy { it.name.lowercase() }
        _uiState.update { state ->
            if (force || state.connectionState == ChatConnectionState.CREATING_GROUP ||
                state.connectionState == ChatConnectionState.GROUP_SETTINGS
            ) {
                state.copy(groupContacts = contacts)
            } else {
                state
            }
        }
    }

    private fun reloadSavedContactsNow() {
        val contacts = chatStore.getSavedContacts()
        _uiState.update { state -> state.copy(savedContacts = contacts) }
    }

    private fun currentProfile(): ContactProfile = _uiState.value.let {
        ContactProfile(
            displayName = it.displayName,
            phoneNumber = it.phoneNumber,
            email = it.profileEmail,
            bio = it.profileBio,
            websiteUrl = it.profileWebsite,
            instagramUrl = it.profileInstagram,
            xUrl = it.profileX,
            linkedinUrl = it.profileLinkedin,
            githubUrl = it.profileGithub,
        )
    }

    private fun stopChat() {
        nearbyChatController.stop()
        connectedPeers.clear()
        _uiState.update {
            it.copy(
                connectionState = ChatConnectionState.IDLE,
                discoveredDevices = emptyList(),
                selectedPeerId = null,
                authenticationDigits = null,
                messages = emptyList(),
                directConnectionCount = 0,
                conversations = it.conversations.map { conversation ->
                    conversation.copy(connected = false)
                },
            )
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        const val MAX_MESSAGE_LENGTH = 1_000
        const val MAX_GROUP_NAME_LENGTH = 40
        const val MAX_PHONE_LENGTH = 24
        const val MAX_EMAIL_LENGTH = 120
        const val MAX_BIO_LENGTH = 240
        const val MAX_URL_LENGTH = 200

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return ChatViewModel(
                    nearbyChatController = NearbyChatManager(appContext),
                    chatStore = SqliteChatStore(appContext),
                    identityStore = LocalIdentityStore(appContext),
                ) as T
            }
        }
    }
}
