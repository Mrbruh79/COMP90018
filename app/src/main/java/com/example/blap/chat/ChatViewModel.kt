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
import com.example.blap.event.EventAdminKeyStore
import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation
import com.example.blap.event.EventCoordinator
import com.example.blap.event.EventRemoteRepository
import com.example.blap.event.EventStore
import com.example.blap.event.EventUiState
import com.example.blap.event.FirebaseEventRemoteRepository
import com.example.blap.event.LocalEventAdminKeyStore
import com.example.blap.event.SqliteEventStore
import com.example.blap.auth.AuthManager

class ChatViewModel(
    private val nearbyChatController: NearbyChatController,
    private val chatStore: ChatStore,
    private val identityStore: IdentityStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    eventStore: EventStore? = null,
    eventRemoteRepository: EventRemoteRepository? = null,
    eventAdminKeyStore: EventAdminKeyStore? = null,
    private val cloudChatController: CloudChatController? = null,
    initialAccountId: String = "",
) : ViewModel(), NearbyChatController.Listener, CloudChatController.Listener {
    private val workScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val connectedPeers = ConcurrentHashMap<String, ConnectedPeer>()
    private val cloudUploadsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val localPeerId = identityStore.getPeerId()
    private var localPhoneHash = PhoneIdentity.hash(identityStore.getPhoneNumber()).orEmpty()
    private val initialProfile = identityStore.getProfile()
    private var profileReturnScreen = ChatScreen.SHOWING_MY_CARD
    private var requestedEndpointId: String? = null
    private var scannedPeerId: String? = null
    private var scannedPhoneHash: String? = null
    private var scannedEmail: String? = null
    private val eventCoordinator = if (
        eventStore != null && eventRemoteRepository != null && eventAdminKeyStore != null
    ) {
        EventCoordinator(
            eventStore = eventStore,
            remoteRepository = eventRemoteRepository,
            adminKeyStore = eventAdminKeyStore,
            identityStore = identityStore,
            nearbyController = nearbyChatController,
            scope = workScope,
        )
    } else null
    private val emptyEventUiState = MutableStateFlow(EventUiState())
    val eventUiState: StateFlow<EventUiState> = eventCoordinator?.uiState ?: emptyEventUiState.asStateFlow()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            myPeerId = localPeerId,
            screen = if (initialProfile.displayName.isNotBlank() &&
                (initialProfile.phoneNumber.isBlank() || PhoneIdentity.normalize(initialProfile.phoneNumber) != null)
            ) ChatScreen.CHATS else ChatScreen.WELCOME,
            displayName = initialProfile.displayName,
            phoneNumber = initialProfile.phoneNumber,
            profileEmail = initialProfile.email,
            profileGoogleEmail = initialProfile.googleAccountEmail,
            profileDiscoverableByPhone = initialProfile.discoverableByPhone,
            profileBio = initialProfile.bio,
            profileWebsite = initialProfile.websiteUrl,
            profileInstagram = initialProfile.instagramUrl,
            profileX = initialProfile.xUrl,
            profileLinkedin = initialProfile.linkedinUrl,
            profileGithub = initialProfile.githubUrl,
            onlineAccountId = initialAccountId,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        nearbyChatController.listener = this
        workScope.launch {
            chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
            reloadConversationsNow()
            reloadSavedContactsNow()
            if (initialAccountId.isNotBlank()) {
                cloudChatController?.start(initialAccountId, this@ChatViewModel)
                publishAccountNow()
                syncCloudPendingNow()
            }
        }
    }

    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name.take(MAX_NAME_LENGTH), nameError = null) }
    }

    fun updatePhoneNumber(phoneNumber: String) {
        _uiState.update { it.copy(phoneNumber = phoneNumber.take(MAX_PHONE_LENGTH), phoneError = null) }
    }

    fun accountChanged(accountId: String) {
        val previous = _uiState.value.onlineAccountId
        if (previous != accountId) {
            cloudChatController?.stop()
            _uiState.update { it.copy(onlineAccountId = accountId) }
            if (accountId.isNotBlank()) cloudChatController?.start(accountId, this)
        }
        if (accountId.isNotBlank()) workScope.launch {
            publishAccountNow()
            syncCloudPendingNow()
        }
    }

    private suspend fun publishAccountNow() {
        if (_uiState.value.onlineAccountId.isBlank() || _uiState.value.displayName.isBlank()) return
        runCatching { cloudChatController?.publishAccount(currentProfile(), localPeerId) }
            .onFailure { onCloudError(it.localizedMessage ?: "Could not publish your account details.") }
    }

    fun startChat() {
        if (_uiState.value.nearbyActive) return
        when (val check = checkIdentity()) {
            is IdentityCheck.Invalid -> {
                _uiState.update { it.copy(error = check.nameError ?: check.phoneError) }
                return
            }

            is IdentityCheck.Valid -> saveIdentity(check.name, check.phone)
        }
        if (_uiState.value.screen == ChatScreen.WELCOME) showConversationList()
        val state = _uiState.value
        _uiState.update { it.copy(nearbyActive = true, error = null) }
        nearbyChatController.startAdvertising(state.displayName, localPeerId, localPhoneHash)
        if (_uiState.value.nearbyActive) nearbyChatController.startDiscovery()
    }

    fun completeSetup() {
        when (val check = checkIdentity()) {
            is IdentityCheck.Invalid ->
                _uiState.update { it.copy(nameError = check.nameError, phoneError = check.phoneError) }

            is IdentityCheck.Valid -> {
                saveIdentity(check.name, check.phone)
                showConversationList()
                workScope.launch { publishAccountNow() }
            }
        }
    }

    private fun checkIdentity(): IdentityCheck {
        val name = _uiState.value.displayName.trim()
        val rawPhone = _uiState.value.phoneNumber
        val phone = if (rawPhone.isBlank()) "" else PhoneIdentity.normalizeInternational(rawPhone)
        if (name.isBlank() || phone == null) {
            return IdentityCheck.Invalid(
                nameError = "Please enter a display name".takeIf { name.isBlank() },
                phoneError = "Enter a valid phone number with your country code".takeIf { phone == null },
            )
        }
        return IdentityCheck.Valid(name, phone)
    }

    private fun saveIdentity(name: String, phone: String) {
        identityStore.saveProfile(currentProfile().copy(displayName = name, phoneNumber = phone))
        localPhoneHash = PhoneIdentity.hash(phone).orEmpty()
        _uiState.update {
            it.copy(
                displayName = name,
                phoneNumber = phone,
                error = null,
                nameError = null,
                phoneError = null,
            )
        }
    }

    private sealed interface IdentityCheck {
        data class Valid(val name: String, val phone: String) : IdentityCheck
        data class Invalid(val nameError: String?, val phoneError: String?) : IdentityCheck
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
        nearbyChatController.connectToDevice(device.endpointId)
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

    fun showEvents() {
        _uiState.update { it.copy(screen = ChatScreen.EVENTS, error = null) }
        eventCoordinator?.showList()
    }

    fun beginCreateEvent() = eventCoordinator?.beginCreate() ?: Unit
    fun beginEditEvent() = eventCoordinator?.beginEdit() ?: Unit

    fun createEvent(
        title: String,
        description: String,
        venueName: String,
        latitude: Double,
        longitude: Double,
        radiusMetres: Double,
        startsAt: Long,
        endsAt: Long,
    ) = eventCoordinator?.createEvent(
        title,
        description,
        venueName,
        latitude,
        longitude,
        radiusMetres,
        startsAt,
        endsAt,
    ) ?: Unit

    fun openEvent(eventId: String) = eventCoordinator?.openEvent(eventId) ?: Unit
    fun updateSelectedEvent(request: EventCreateRequest) = eventCoordinator?.updateSelectedEvent(request) ?: Unit
    fun deleteSelectedEvent() = eventCoordinator?.deleteSelectedEvent() ?: Unit
    fun joinSelectedEvent() = eventCoordinator?.joinSelectedEvent() ?: Unit
    fun leaveSelectedEvent() = eventCoordinator?.leaveSelectedEvent() ?: Unit
    fun promoteEventMember(userId: String) = eventCoordinator?.promoteMemberToCoAdmin(userId) ?: Unit
    fun removeEventMember(userId: String) = eventCoordinator?.blockMember(userId) ?: Unit
    fun deleteSelectedEventData() = eventCoordinator?.deleteSelectedEventData() ?: Unit
    fun showEventAnnouncements() = eventCoordinator?.showAnnouncements() ?: Unit
    fun publishEventAnnouncement(text: String) = eventCoordinator?.publishAnnouncement(text) ?: Unit
    fun enterEventWithGps(latitude: Double, longitude: Double, accuracyMetres: Double) =
        eventCoordinator?.enterWithGps(latitude, longitude, accuracyMetres) ?: Unit

    fun enterEventWithQr(payload: String) = eventCoordinator?.enterWithQr(payload) ?: Unit
    fun createEventCheckInQr(): String? = eventCoordinator?.createVenueCheckInQr()
    fun showEventCheckInQr() = eventCoordinator?.showVenueCheckInQr() ?: Unit
    fun hideEventCheckInQr() = eventCoordinator?.hideVenueCheckInQr() ?: Unit
    fun sendEventMessage(text: String) = eventCoordinator?.sendOnSiteMessage(text) ?: Unit
    fun showSavedEventChat() = eventCoordinator?.showSavedOnSiteHistory() ?: Unit
    fun eventBack() = eventCoordinator?.back() ?: Unit
    fun dismissEventMessage() = eventCoordinator?.dismissMessage() ?: Unit

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
        scannedPeerId = null
        scannedPhoneHash = null
        scannedEmail = null
        _uiState.update {
            it.copy(
                screen = ChatScreen.EDITING_CONTACT,
                selectedContactId = null,
                contactNameDraft = "",
                contactPhoneDraft = "",
                contactEmailDraft = "",
                contactGoogleEmailDraft = "",
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
        scannedPeerId = null
        scannedPhoneHash = null
        scannedEmail = null
        val contact = _uiState.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        _uiState.update {
            it.copy(
                screen = ChatScreen.EDITING_CONTACT,
                selectedContactId = contact.id,
                contactNameDraft = contact.name,
                contactPhoneDraft = contact.phoneNumber,
                contactEmailDraft = contact.email,
                contactGoogleEmailDraft = contact.googleAccountEmail,
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
        val peerId = contact.linkedPeerId ?: ContactIdentity.localPeerId(
            contact.phoneHash, contact.email, contact.googleAccountEmail,
        ) ?: return
        if (contact.linkedPeerId == null && _uiState.value.onlineAccountId.isBlank()) {
            _uiState.update { it.copy(notice = "Sign in with Email or Google, or pair by QR/Nearby, to message this contact.") }
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
                contactGoogleEmailDraft = profile.googleAccountEmail.take(MAX_EMAIL_LENGTH),
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
        val card = ContactCardCodec.decodeCard(payload)
        if (card == null) {
            showError("That QR code is not a BLAP contact card.")
            return
        }
        beginAddContact()
        updateContactDraft(card.profile)
        scannedPeerId = card.peerId.takeIf(String::isNotBlank)
        scannedPhoneHash = PhoneIdentity.hash(card.profile.phoneNumber)
        scannedEmail = ContactIdentity.normalizeEmail(card.profile.email)
            ?: ContactIdentity.normalizeEmail(card.profile.googleAccountEmail)
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
        val normalizedPhone = if (state.contactPhoneDraft.isBlank()) "" else
            PhoneIdentity.normalizeInternational(state.contactPhoneDraft)
        val email = if (state.contactEmailDraft.isBlank()) "" else
            ContactIdentity.normalizeEmail(state.contactEmailDraft)
        val googleEmail = if (state.contactGoogleEmailDraft.isBlank()) "" else
            ContactIdentity.normalizeEmail(state.contactGoogleEmailDraft)
        if (name.isBlank() || normalizedPhone == null || email == null || googleEmail == null ||
            (normalizedPhone.isBlank() && email.isBlank() && googleEmail.isBlank() && scannedPeerId == null)
        ) {
            _uiState.update { it.copy(error = "Enter a name and at least one valid phone or email address.") }
            return
        }
        val phoneHash = PhoneIdentity.hash(normalizedPhone).orEmpty()
        workScope.launch {
            val linkedPeerId = scannedPeerId?.takeIf {
                (phoneHash.isNotBlank() && scannedPhoneHash == phoneHash) ||
                    (scannedEmail != null && (scannedEmail == email || scannedEmail == googleEmail)) ||
                    (phoneHash.isBlank() && email.isBlank() && googleEmail.isBlank())
            } ?: chatStore.getKnownContacts()
                .firstOrNull { phoneHash.isNotBlank() && it.phoneHash == phoneHash }
                ?.peerId
            val existing = state.selectedContactId?.let { id ->
                state.savedContacts.firstOrNull { it.id == id }
            }
            val retainedPeerId = existing?.linkedPeerId?.takeIf {
                (phoneHash.isNotBlank() && existing.phoneHash == phoneHash) ||
                    (email.isNotBlank() && existing.email == email) ||
                    (googleEmail.isNotBlank() && existing.googleAccountEmail == googleEmail)
            }
            chatStore.saveContact(
                SavedContact(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    phoneNumber = normalizedPhone,
                    phoneHash = phoneHash,
                    linkedPeerId = retainedPeerId ?: linkedPeerId,
                    email = email,
                    googleAccountEmail = googleEmail,
                    cloudUserId = existing?.cloudUserId.orEmpty().takeIf {
                        (phoneHash.isNotBlank() && existing?.phoneHash == phoneHash) ||
                            (email.isNotBlank() && existing?.email == email) ||
                            (googleEmail.isNotBlank() && existing?.googleAccountEmail == googleEmail)
                    }.orEmpty(),
                    bio = state.contactBioDraft.trim(),
                    websiteUrl = ProfileUrl.normalize(state.contactWebsiteDraft),
                    instagramUrl = ProfileUrl.normalize(state.contactInstagramDraft),
                    xUrl = ProfileUrl.normalize(state.contactXDraft),
                    linkedinUrl = ProfileUrl.normalize(state.contactLinkedinDraft),
                    githubUrl = ProfileUrl.normalize(state.contactGithubDraft),
                    source = state.contactSourceDraft,
                ),
            )
            reloadSavedContactsNow()
            val syntheticPeerId = ContactIdentity.localPeerId(phoneHash, email, googleEmail)
            val actualPeerId = retainedPeerId ?: linkedPeerId
            if (actualPeerId != null && syntheticPeerId != null) {
                chatStore.moveConversation(syntheticPeerId, actualPeerId)
            }
            syncCloudPendingNow()
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
            val existingContacts = chatStore.getSavedContacts()
            val existingHashes = existingContacts.map(SavedContact::phoneHash).filter(String::isNotBlank).toMutableSet()
            val existingEmails = existingContacts.flatMap { listOf(it.email, it.googleAccountEmail) }
                .filter(String::isNotBlank).toMutableSet()
            var imported = 0
            contacts.forEach { contact ->
                val normalized = if (contact.phoneNumber.isBlank()) "" else
                    PhoneIdentity.normalize(contact.phoneNumber) ?: return@forEach
                val hash = PhoneIdentity.hash(normalized).orEmpty()
                val email = if (contact.email.isBlank()) "" else
                    ContactIdentity.normalizeEmail(contact.email) ?: return@forEach
                if (hash.isBlank() && email.isBlank()) return@forEach
                if ((hash.isNotBlank() && hash in existingHashes) ||
                    (hash.isBlank() && email in existingEmails)
                ) return@forEach
                chatStore.saveContact(
                    SavedContact(
                        id = UUID.randomUUID().toString(),
                        name = contact.name.take(MAX_NAME_LENGTH),
                        phoneNumber = normalized,
                        phoneHash = hash,
                        linkedPeerId = meshByHash[hash]?.peerId,
                        email = email,
                        source = ContactSource.DEVICE,
                    ),
                )
                if (hash.isNotBlank()) existingHashes += hash
                if (email.isNotBlank()) existingEmails += email
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
            if (selected.size > 7) {
                showError("Private groups can have up to eight members, including you.")
                return@launch
            }
            val group = current.copy(
                name = name,
                createdAt = System.currentTimeMillis(),
                cloudSynced = false,
                members = listOf(GroupMember(localPeerId, state.displayName, localPhoneHash)) +
                    selected.map { GroupMember(it.peerId, it.name, it.phoneHash) },
            )
            chatStore.saveGroup(group)
            nearbyChatController.publishGroup(group)
            workScope.launch { uploadGroup(group) }
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
            if (group.cloudSynced && group.ownerAccountId.isNotBlank() &&
                group.ownerAccountId == _uiState.value.onlineAccountId) {
                workScope.launch {
                    runCatching { cloudChatController?.deleteGroup(groupId) }
                        .onFailure { onCloudError(it.localizedMessage ?: "Could not remove the online group.") }
                }
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
            googleAccountEmail = profile.googleAccountEmail.take(MAX_EMAIL_LENGTH),
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
                profileGoogleEmail = profile.googleAccountEmail.take(MAX_EMAIL_LENGTH),
                profileDiscoverableByPhone = profile.discoverableByPhone,
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
        val normalizedPhone = if (profile.phoneNumber.isBlank()) "" else
            PhoneIdentity.normalizeInternational(profile.phoneNumber)
        val email = if (profile.email.isBlank()) "" else ContactIdentity.normalizeEmail(profile.email)
        val googleEmail = if (profile.googleAccountEmail.isBlank()) "" else
            ContactIdentity.normalizeEmail(profile.googleAccountEmail)
        if (profile.displayName.trim().isBlank() || normalizedPhone == null || email == null || googleEmail == null) {
            showError("Enter your name and check any phone or email addresses you added.")
            return
        }
        val saved = profile.copy(
            displayName = profile.displayName.trim(),
            phoneNumber = normalizedPhone,
            email = email,
            googleAccountEmail = googleEmail,
            discoverableByPhone = profile.discoverableByPhone && normalizedPhone.isNotBlank(),
            bio = profile.bio.trim(),
            websiteUrl = ProfileUrl.normalize(profile.websiteUrl),
            instagramUrl = ProfileUrl.normalize(profile.instagramUrl),
            xUrl = ProfileUrl.normalize(profile.xUrl),
            linkedinUrl = ProfileUrl.normalize(profile.linkedinUrl),
            githubUrl = ProfileUrl.normalize(profile.githubUrl),
        )
        identityStore.saveProfile(saved)
        localPhoneHash = PhoneIdentity.hash(normalizedPhone).orEmpty()
        applyProfile(saved)
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
            if (_uiState.value.nearbyActive) nearbyChatController.startDiscovery()
        }
        _uiState.update { it.copy(screen = profileReturnScreen, profileDraft = null, notice = "Profile saved.") }
        workScope.launch { publishAccountNow() }
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
        if (selectedContacts.size > 7) {
            showError("Private groups can have up to eight members, including you.")
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
            ownerAccountId = state.onlineAccountId,
        )
        workScope.launch {
            chatStore.saveGroup(group)
            nearbyChatController.publishGroup(group)
            workScope.launch { uploadGroup(group) }
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
            ChatScreen.EVENTS,
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
            senderAccountId = _uiState.value.onlineAccountId,
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
            workScope.launch { uploadCloudMessage(message) }
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
        _uiState.update { it.copy(error = null, notice = null) }
    }

    fun updateMessageDraft(text: String) {
        val peerId = _uiState.value.selectedPeerId ?: return
        _uiState.update { it.copy(messageDrafts = it.messageDrafts + (peerId to text.take(MAX_MESSAGE_LENGTH))) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun showNotice(message: String) {
        _uiState.update { it.copy(notice = message) }
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
            if (peer.phoneHash.isNotBlank()) {
                movePhoneConversationToPeer(peer.phoneHash, peer.peerId)
            }
            reloadConversationsNow()
            reloadSavedContactsNow()
            if (_uiState.value.selectedPeerId == peer.peerId) reloadMessagesNow(peer.peerId)
            chatStore.getPendingMessages(peer.peerId).forEach(::sendStoredMessage)
            synchronizeGroupsWith(peer.peerId)
            syncCloudPendingNow()
        }
    }

    override fun onGroupReceived(group: PrivateGroup) {
        if (group.members.none {
                it.peerId == localPeerId || (localPhoneHash.isNotBlank() && it.phoneHash == localPhoneHash)
            }
        ) return
        workScope.launch {
            chatStore.saveGroup(group)
            reloadConversationsNow()
        }
    }

    override fun onMeshPeerFound(peer: GroupMember) {
        if (peer.peerId == localPeerId) return
        workScope.launch {
            chatStore.saveMeshPeer(peer.peerId, peer.name, peer.phoneHash)
            if (peer.phoneHash.isNotBlank()) {
                chatStore.linkContact(peer.phoneHash, peer.peerId)
                movePhoneConversationToPeer(peer.phoneHash, peer.peerId)
            }
            reloadSavedContactsNow()
            reloadConversationsNow()
            if (_uiState.value.screen == ChatScreen.CREATING_GROUP) {
                reloadGroupContactsNow()
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

    override fun onEventPeerAvailable(peerId: String, eventId: String) {
        eventCoordinator?.onEventPeerAvailable(peerId, eventId)
    }

    override fun onEventChatMessageReceived(message: EventChatMessage) {
        eventCoordinator?.onEventChatMessageReceived(message)
    }

    override fun onEventAnnouncementReceived(announcement: EventAnnouncement) {
        eventCoordinator?.onEventAnnouncementReceived(announcement)
    }

    override fun onEventMutationReceived(mutation: EventMutation) {
        eventCoordinator?.onEventMutationReceived(mutation)
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

    override fun onDirectMessage(otherUid: String, message: CloudChatMessage) {
        workScope.launch {
            val contact = chatStore.getSavedContacts().firstOrNull { it.cloudUserId == otherUid }
            val account = runCatching { cloudChatController?.getAccount(otherUid) }.getOrNull()
            val peerId = contact?.linkedPeerId ?: contact?.let {
                ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail)
            } ?: account?.peerId?.takeIf(String::isNotBlank) ?: "account:$otherUid"
            chatStore.savePeer(peerId, contact?.name ?: account?.name ?: "Online contact", contact?.phoneHash.orEmpty())
            val inserted = chatStore.saveMessage(
                message.toLocalMessage(peerId, _uiState.value.onlineAccountId),
            )
            if (inserted) {
                reloadSavedContactsNow()
                reloadConversationsNow()
                if (_uiState.value.selectedPeerId == peerId) reloadMessagesNow(peerId)
            }
        }
    }

    override fun onPrivateGroup(group: CloudPrivateGroup) {
        workScope.launch {
            val localUid = _uiState.value.onlineAccountId
            if (group.members.none { it.uid == localUid }) return@launch
            val contacts = chatStore.getSavedContacts().filter { it.cloudUserId.isNotBlank() }
                .associateBy(SavedContact::cloudUserId)
            val members = group.members.map { member ->
                val contact = contacts[member.uid]
                GroupMember(
                    peerId = if (member.uid == localUid) localPeerId else
                        contact?.linkedPeerId ?: contact?.let {
                            ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail)
                        } ?: member.peerId.ifBlank { "account:${member.uid}" },
                    name = contact?.name ?: member.name.ifBlank { "Online contact" },
                    phoneHash = contact?.phoneHash.orEmpty(),
                )
            }
            val ownerId = members.getOrNull(group.members.indexOfFirst { it.uid == group.ownerUid })?.peerId
                ?: return@launch
            chatStore.saveGroup(PrivateGroup(group.id, group.name, ownerId, group.revision, members,
                cloudSynced = true, ownerAccountId = group.ownerUid))
            reloadConversationsNow()
            syncCloudPendingNow()
        }
    }

    override fun onGroupMessage(groupId: String, message: CloudChatMessage) {
        workScope.launch {
            val inserted = chatStore.saveMessage(message.toLocalMessage(groupId, _uiState.value.onlineAccountId))
            if (inserted) {
                reloadConversationsNow()
                if (_uiState.value.selectedPeerId == groupId) reloadMessagesNow(groupId)
            }
        }
    }

    override fun onCloudError(message: String) {
        _uiState.update { it.copy(notice = "Online chat: $message") }
    }

    private fun CloudChatMessage.toLocalMessage(conversationId: String, localUid: String): ChatMessage =
        ChatMessage(
            id = id,
            peerId = conversationId,
            text = text,
            author = if (senderUid == localUid) MessageAuthor.ME else MessageAuthor.PEER,
            sentAt = sentAt,
            status = if (senderUid == localUid) MessageStatus.SENT else MessageStatus.DELIVERED,
            senderId = senderPeerId,
            senderName = senderName,
            senderAccountId = senderUid,
            cloudSynced = true,
        )

    private fun movePhoneConversationToPeer(phoneHash: String, peerId: String) {
        val oldId = "phone:$phoneHash"
        chatStore.moveConversation(oldId, peerId)
        _uiState.update { state ->
            if (state.selectedPeerId == oldId) state.copy(selectedPeerId = peerId) else state
        }
        if (_uiState.value.selectedPeerId == peerId) reloadMessagesNow(peerId)
    }

    private fun syncCloudPendingNow() {
        val uid = _uiState.value.onlineAccountId
        if (uid.isBlank() || cloudChatController == null) return
        chatStore.getCloudPendingGroups().filter {
            it.ownerId == localPeerId && it.ownerAccountId == uid
        }.forEach { group ->
            workScope.launch { uploadGroup(group) }
        }
        chatStore.getCloudPendingMessages().filter { it.senderAccountId == uid }.forEach { message ->
            workScope.launch { uploadCloudMessage(message) }
        }
    }

    private suspend fun uploadGroup(group: PrivateGroup): Boolean {
        val controller = cloudChatController ?: return false
        val localUid = _uiState.value.onlineAccountId
        if (localUid.isBlank() || group.ownerId != localPeerId || group.ownerAccountId != localUid) return false
        if (group.members.none { it.peerId == localPeerId }) return false
        val contacts = chatStore.getSavedContacts()
        val members = group.members.map { member ->
            if (member.peerId == localPeerId) CloudGroupMember(localUid, localPeerId, member.name)
            else {
                val contact = contacts.firstOrNull {
                    it.linkedPeerId == member.peerId ||
                        ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail) == member.peerId ||
                        (member.phoneHash.isNotBlank() && it.phoneHash == member.phoneHash)
                }
                val account = resolveAccount(controller, contact, member.peerId) ?: return false
                CloudGroupMember(account.uid, member.peerId, member.name)
            }
        }
        return runCatching {
            controller.saveGroup(CloudPrivateGroup(group.id, group.name, localUid, group.createdAt, members))
            chatStore.markGroupCloudSynced(group.id, group.createdAt)
            true
        }.getOrElse {
            onCloudError(it.localizedMessage ?: "Could not upload the private group.")
            false
        }
    }

    private suspend fun uploadCloudMessage(message: ChatMessage) {
        val controller = cloudChatController ?: return
        val localUid = _uiState.value.onlineAccountId
        if (localUid.isBlank() || message.senderAccountId != localUid ||
            message.cloudSynced || !cloudUploadsInFlight.add(message.id)
        ) return
        try {
            val cloudMessage = CloudChatMessage(
                message.id, localUid, localPeerId, _uiState.value.displayName,
                message.text, message.sentAt,
            )
            val group = chatStore.getGroups().firstOrNull { it.id == message.peerId }
            if (group != null) {
                if (group.ownerId == localPeerId && !group.cloudSynced && !uploadGroup(group)) return
                controller.sendGroup(group.id, cloudMessage)
            } else if (message.peerId != MeshGroup.ID) {
                val contact = chatStore.getSavedContacts().firstOrNull {
                    it.linkedPeerId == message.peerId ||
                        ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail) == message.peerId
                }
                val account = resolveAccount(controller, contact, message.peerId) ?: return
                controller.sendDirect(account.uid, cloudMessage)
            } else return
            chatStore.markCloudSynced(message.id)
            setMessageStatus(message.peerId, message.id, MessageStatus.SENT)
        } catch (error: Exception) {
            onCloudError(error.localizedMessage ?: "A message will retry when online.")
        } finally {
            cloudUploadsInFlight.remove(message.id)
        }
    }

    private suspend fun resolveAccount(
        controller: CloudChatController,
        contact: SavedContact?,
        peerId: String,
    ): CloudAccount? {
        if (!contact?.cloudUserId.isNullOrBlank()) {
            controller.getAccount(contact.cloudUserId)?.let { return it }
        }
        val pairedPeerId = contact?.linkedPeerId ?: peerId.takeUnless {
            it.startsWith("phone:") || it.startsWith("email:") || it.startsWith("account:")
        }
        val lookups = listOfNotNull(
            pairedPeerId?.takeIf(String::isNotBlank)?.let { "peer" to it },
            contact?.email?.takeIf(String::isNotBlank)?.let { "email" to it },
            contact?.googleAccountEmail?.takeIf(String::isNotBlank)?.let { "email" to it },
            contact?.phoneNumber?.takeIf(String::isNotBlank)?.let { "phone" to it },
        ).distinct()
        var matchedByPhone = false
        var account: CloudAccount? = null
        for ((type, value) in lookups) {
            val candidates = when (type) {
                "peer" -> controller.findAccounts(peerId = value)
                "email" -> controller.findAccounts(email = value)
                else -> controller.findAccounts(phoneNumber = value)
            }
            if (candidates.size > 1) {
                onCloudError("Several accounts match this contact. Use a QR card to choose the right person.")
                return null
            }
            if (candidates.size == 1) {
                account = candidates.single()
                matchedByPhone = type == "phone"
                break
            }
        }
        if (account == null) {
            onCloudError("No online account found for this contact. They can share a QR card or enable phone lookup.")
            return null
        }
        if (contact != null && contact.cloudUserId != account.uid) {
            chatStore.saveContact(contact.copy(cloudUserId = account.uid))
            reloadSavedContactsNow()
        }
        if (matchedByPhone) {
            onCloudError("This number match is not verified. Confirm the person with their QR card.")
        }
        return account
    }

    override fun onCleared() {
        cloudChatController?.stop()
        nearbyChatController.close()
        workScope.cancel()
        chatStore.close()
        eventCoordinator?.close()
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
                ?: knownContacts.values.firstOrNull {
                    contact.phoneHash.isNotBlank() && it.phoneHash == contact.phoneHash
                }?.peerId
            val contactKey = contact.phoneHash.ifBlank { "contact:${contact.id}" }
            contactsByHash[contactKey] = GroupContact(
                peerId = linkedPeerId ?: if (contact.phoneHash.isNotBlank()) "phone:${contact.phoneHash}"
                    else "contact:${contact.id}",
                name = contact.name,
                connected = linkedPeerId?.let(connectedPeers::containsKey) == true,
                phoneNumber = contact.phoneNumber,
                phoneHash = contact.phoneHash,
                email = contact.email.ifBlank { contact.googleAccountEmail },
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
                email = contact.email,
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
            googleAccountEmail = it.profileGoogleEmail,
            discoverableByPhone = it.profileDiscoverableByPhone,
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
        nearbyChatController.stop()
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

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return ChatViewModel(
                    nearbyChatController = NearbyChatManager(appContext),
                    chatStore = SqliteChatStore(appContext),
                    identityStore = LocalIdentityStore(appContext),
                    eventStore = SqliteEventStore(appContext),
                    eventRemoteRepository = FirebaseEventRemoteRepository(),
                    eventAdminKeyStore = LocalEventAdminKeyStore(appContext),
                    cloudChatController = FirebaseCloudChatController(),
                    initialAccountId = AuthManager.onlineUserId,
                ) as T
            }
        }
    }
}
