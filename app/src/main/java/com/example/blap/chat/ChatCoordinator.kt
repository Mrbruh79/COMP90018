package com.example.blap.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatCoordinator(
    private val chatStore: ChatStore,
    private val identityStore: IdentityStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialNearbyChatController: NearbyChatController? = null,
) : NearbyChatController.Listener {
    @Volatile
    private var nearbyChatController: NearbyChatController? = null
    private val workScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectedPeers = ConcurrentHashMap<String, ConnectedPeer>()
    private val localPeerId = identityStore.getPeerId()
    private var localPhoneHash = PhoneIdentity.hash(identityStore.getPhoneNumber()).orEmpty()
    private val initialProfile = identityStore.getProfile()
    private var profileReturnScreen = ChatScreen.SHOWING_MY_CARD
    private var requestedEndpointId: String? = null

    private val _uiState = MutableStateFlow(
        ChatUiState(
            screen = if (initialProfile.displayName.isNotBlank() &&
                PhoneIdentity.normalize(initialProfile.phoneNumber) != null
            ) ChatScreen.CHATS else ChatScreen.WELCOME,
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
    private val _acceptedIncomingMessages = MutableSharedFlow<AcceptedIncomingMessage>(
        extraBufferCapacity = 64,
    )
    val acceptedIncomingMessages: SharedFlow<AcceptedIncomingMessage> =
        _acceptedIncomingMessages.asSharedFlow()

    init {
        initialNearbyChatController?.let(::attachNearbyController)
        workScope.launch {
            chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
            reloadConversationsNow()
            reloadSavedContactsNow()
        }
    }

    fun attachNearbyController(controller: NearbyChatController) {
        if (nearbyChatController === controller) return
        check(nearbyChatController == null) { "A Nearby controller is already attached." }
        nearbyChatController = controller
        controller.listener = this
    }

    fun detachNearbyController(controller: NearbyChatController) {
        if (nearbyChatController !== controller) return
        controller.listener = null
        nearbyChatController = null
    }

    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name.take(MAX_NAME_LENGTH)) }
    }

    fun updatePhoneNumber(phoneNumber: String) {
        _uiState.update { it.copy(phoneNumber = phoneNumber.take(MAX_PHONE_LENGTH)) }
    }

    fun startChat(): Boolean {
        if (_uiState.value.nearbyActive) return true
        if (!saveIdentity()) return false
        val controller = nearbyChatController ?: return false
        if (_uiState.value.screen == ChatScreen.WELCOME) showConversationList()
        val state = _uiState.value
        _uiState.update { it.copy(nearbyActive = true, error = null) }
        controller.startAdvertising(state.displayName, localPeerId, localPhoneHash)
        if (_uiState.value.nearbyActive) controller.startDiscovery()
        return true
    }

    fun completeSetup() {
        if (saveIdentity()) showConversationList()
    }

    private fun saveIdentity(): Boolean {
        val name = _uiState.value.displayName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Enter a display name first.") }
            return false
        }
        val normalizedPhone = PhoneIdentity.normalize(_uiState.value.phoneNumber)
        if (normalizedPhone == null) {
            _uiState.update { it.copy(error = "Enter a valid phone number, including country code.") }
            return false
        }

        identityStore.saveProfile(currentProfile().copy(displayName = name, phoneNumber = normalizedPhone))
        localPhoneHash = requireNotNull(PhoneIdentity.hash(normalizedPhone))
        _uiState.update {
            it.copy(
                displayName = name,
                phoneNumber = normalizedPhone,
                error = null,
            )
        }
        return true
    }

    fun connectToDevice(endpointId: String) {
        val device = _uiState.value.discoveredDevices.firstOrNull { it.endpointId == endpointId }
            ?: return
        requestedEndpointId = endpointId
        _uiState.update {
            it.copy(
                screen = ChatScreen.CONNECTING,
                authenticationDigits = null,
                error = null,
            )
        }
        nearbyChatController?.connectToDevice(device.endpointId)
    }

    fun openConversation(peerId: String) {
        if (_uiState.value.conversations.none { it.peerId == peerId }) return
        _uiState.update {
            it.copy(
                screen = ChatScreen.CONVERSATION,
                selectedPeerId = peerId,
                messages = emptyList(),
                error = null,
            )
        }
        reloadMessages(peerId)
    }

    fun showConversationList() {
        requestedEndpointId = null
        _uiState.update {
            it.copy(
                screen = ChatScreen.CHATS,
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
                screen = ChatScreen.MANAGING_CONTACTS,
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
                screen = ChatScreen.EDITING_CONTACT,
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
                screen = ChatScreen.EDITING_CONTACT,
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

    fun messageContact(contactId: String) {
        val contact = _uiState.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        val peerId = contact.linkedPeerId
        if (peerId == null) {
            showError("Connect to this person nearby once to start a direct chat.")
            return
        }
        workScope.launch {
            chatStore.savePeer(peerId, contact.name, contact.phoneHash)
            reloadConversationsNow()
            openConversation(peerId)
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
                    linkedPeerId = if (existing?.phoneHash == phoneHash) {
                        existing.linkedPeerId ?: linkedPeerId
                    } else linkedPeerId,
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
            reloadConversationsNow()
            _uiState.update {
                it.copy(
                    screen = ChatScreen.MANAGING_CONTACTS,
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
                it.copy(screen = ChatScreen.MANAGING_CONTACTS, selectedContactId = null)
            }
        }
    }

    fun importDeviceContacts(contacts: List<DeviceContact>) {
        workScope.launch {
            val meshByHash = chatStore.getKnownContacts()
                .filter { it.phoneHash.isNotBlank() }
                .associateBy(GroupMember::phoneHash)
            val existingHashes = chatStore.getSavedContacts().map(SavedContact::phoneHash).toMutableSet()
            var imported = 0
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
                imported++
            }
            reloadSavedContactsNow()
            _uiState.update { it.copy(notice = if (imported == 0) "No new contacts to import." else
                "$imported contact${if (imported == 1) "" else "s"} imported.") }
        }
    }

    fun beginCreateGroup() {
        _uiState.update {
            it.copy(
                screen = ChatScreen.CREATING_GROUP,
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
                val candidates = it.groupContacts.toMutableList()
                group.members.filterNot { member -> member.peerId == localPeerId }.forEach { member ->
                    if (candidates.none { contact -> contact.peerId == member.peerId }) {
                        candidates += GroupContact(member.peerId, member.name, false, phoneHash = member.phoneHash)
                    }
                }
                it.copy(
                    screen = ChatScreen.GROUP_SETTINGS,
                    canEditGroup = group.ownerId == localPeerId,
                    groupContacts = candidates,
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
            nearbyChatController?.publishGroup(group)
            reloadConversationsNow()
            _uiState.update { it.copy(screen = ChatScreen.CONVERSATION) }
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
        _uiState.update { it.copy(screen = ChatScreen.SHOWING_MY_CARD, error = null) }
    }

    fun editProfile() {
        profileReturnScreen = _uiState.value.screen
        _uiState.update { it.copy(screen = ChatScreen.EDITING_PROFILE, profileDraft = currentProfile(), error = null) }
    }

    fun updateProfile(profile: ContactProfile) {
        _uiState.update { it.copy(profileDraft = profile.copy(
            displayName = profile.displayName.take(MAX_NAME_LENGTH),
            phoneNumber = profile.phoneNumber.take(MAX_PHONE_LENGTH),
            email = profile.email.take(MAX_EMAIL_LENGTH),
            bio = profile.bio.take(MAX_BIO_LENGTH),
            websiteUrl = profile.websiteUrl.take(MAX_URL_LENGTH),
            instagramUrl = profile.instagramUrl.take(MAX_URL_LENGTH),
            xUrl = profile.xUrl.take(MAX_URL_LENGTH),
            linkedinUrl = profile.linkedinUrl.take(MAX_URL_LENGTH),
            githubUrl = profile.githubUrl.take(MAX_URL_LENGTH),
        )) }
    }

    fun cancelProfileEdit() {
        _uiState.update { it.copy(screen = profileReturnScreen, profileDraft = null, error = null) }
    }

    private fun applyProfile(profile: ContactProfile) {
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
        val wasActive = _uiState.value.nearbyActive
        val profile = _uiState.value.profileDraft ?: currentProfile()
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
        applyProfile(saved)
        if (wasActive) {
            val controller = nearbyChatController
            controller?.stop()
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
            controller?.startAdvertising(saved.displayName, localPeerId, localPhoneHash)
            if (_uiState.value.nearbyActive) controller?.startDiscovery()
        }
        _uiState.update { it.copy(screen = profileReturnScreen, profileDraft = null, notice = "Profile saved.") }
    }

    fun showSettings() {
        _uiState.update { it.copy(screen = ChatScreen.SETTINGS, error = null) }
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
            nearbyChatController?.publishGroup(group)
            reloadConversationsNow()
            _uiState.update {
                it.copy(
                    screen = ChatScreen.CONVERSATION,
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
        when (_uiState.value.screen) {
            ChatScreen.CONVERSATION,
            ChatScreen.CONNECTING,
            ChatScreen.CREATING_GROUP,
            -> showConversationList()

            ChatScreen.MANAGING_CONTACTS,
            ChatScreen.SHOWING_MY_CARD,
            ChatScreen.SETTINGS,
            -> showConversationList()

            ChatScreen.EDITING_CONTACT -> beginManageContacts()
            ChatScreen.EDITING_PROFILE -> cancelProfileEdit()
            ChatScreen.GROUP_SETTINGS -> {
                _uiState.update { it.copy(screen = ChatScreen.CONVERSATION) }
            }

            ChatScreen.CHATS,
            ChatScreen.ERROR,
            -> Unit

            ChatScreen.WELCOME -> Unit
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
                messageDrafts = state.messageDrafts - peerId,
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
        nearbyChatController?.disconnect(peerId)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null, notice = null) }
    }

    fun updateMessageDraft(text: String) {
        val peerId = _uiState.value.selectedPeerId ?: return
        _uiState.update { it.copy(messageDrafts = it.messageDrafts + (peerId to text.take(MAX_MESSAGE_LENGTH))) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun updateVenueStatus(message: String, checking: Boolean = false) {
        _uiState.update { it.copy(venueStatus = message, checkingVenue = checking) }
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
            if (requestedEndpointId == device.endpointId && state.screen == ChatScreen.CONNECTING) {
                state.copy(
                    authenticationDigits = authenticationDigits,
                    error = null,
                )
            } else state
        }
    }

    override fun onConnected(peer: ConnectedPeer) {
        connectedPeers[peer.peerId] = peer
        val shouldOpen = _uiState.value.screen == ChatScreen.CONNECTING && requestedEndpointId == peer.endpointId
        if (shouldOpen) requestedEndpointId = null

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
                screen = if (shouldOpen) ChatScreen.CONVERSATION else state.screen,
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
            reloadSavedContactsNow()
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
            reloadSavedContactsNow()
            if (_uiState.value.screen == ChatScreen.CREATING_GROUP) {
                reloadGroupContactsNow()
            }
        }
    }

    fun receiveIncomingMessage(
        message: IncomingMessageEnvelope,
        source: MessageTransport,
    ) {
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
            source.acknowledgeMessage(
                message.conversationId,
                message.senderId,
                message.messageId,
            )
            if (!inserted) return@launch

            reloadConversationsNow()
            if (_uiState.value.selectedPeerId == message.conversationId) {
                reloadMessagesNow(message.conversationId)
            }
            val conversation = _uiState.value.conversations
                .first { it.peerId == message.conversationId }
            _acceptedIncomingMessages.emit(AcceptedIncomingMessage(savedMessage, conversation))
        }
    }

    override fun onMessageReceived(message: IncomingMessageEnvelope) {
        val source = nearbyChatController ?: return
        receiveIncomingMessage(message, source)
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
            val nextState = if (state.screen == ChatScreen.CONNECTING) {
                requestedEndpointId = null
                ChatScreen.CHATS
            } else {
                state.screen
            }
            state.copy(screen = nextState, authenticationDigits = null, error = message)
        }
    }

    override fun onNearbyUnavailable(message: String) {
        stopChat()
        showError(message)
    }

    fun close() {
        nearbyChatController?.close()
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
        nearbyChatController?.sendMessage(
            OutgoingMessageEnvelope(
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
        val contactNames = chatStore.getSavedContacts().filter { it.linkedPeerId != null }
            .associate { it.linkedPeerId to it.name }
        val conversations = chatStore.getConversations().map { conversation ->
            conversation.copy(
                name = if (conversation.type == ConversationType.DIRECT) {
                    contactNames[conversation.peerId] ?: conversation.name
                } else conversation.name,
                connected = if (conversation.type != ConversationType.DIRECT) connectedPeers.isNotEmpty()
                else connectedPeers.containsKey(conversation.peerId),
            )
        }.sortedByDescending { if (it.lastMessage.isBlank()) 0L else it.lastMessageAt }
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
        nearbyChatController?.synchronizeGroups(peerId, groups, messages)
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
            if (force || state.screen == ChatScreen.CREATING_GROUP ||
                state.screen == ChatScreen.GROUP_SETTINGS
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

    fun stopChat() {
        requestedEndpointId = null
        nearbyChatController?.stop()
        connectedPeers.clear()
        _uiState.update {
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
        const val MAX_NAME_LENGTH = 24
        const val MAX_MESSAGE_LENGTH = 1_000
        const val MAX_GROUP_NAME_LENGTH = 40
        const val MAX_PHONE_LENGTH = 24
        const val MAX_EMAIL_LENGTH = 120
        const val MAX_BIO_LENGTH = 240
        const val MAX_URL_LENGTH = 200
    }
}
