package com.example.blap.chat

import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class ContactsViewModel(private val session: MessagingSession) : androidx.lifecycle.ViewModel() {
    val uiState = session.uiState
    private var scannedPeerId: String? = null
    private var scannedPhoneHash: String? = null
    private var scannedEmail: String? = null
    private var pairedFromOpenChat = false

    fun beginManageContacts() {
        session.contactReturnScreen = ChatScreen.MANAGING_CONTACTS
        session.state.update {
            it.copy(
                screen = ChatScreen.MANAGING_CONTACTS,
                selectedContactId = null,
                contactNameDraft = "",
                contactPhoneDraft = "",
                error = null,
            )
        }
        session.workScope.launch { session.persistence.reloadSavedContactsNow() }
    }

    fun beginAddContact() {
        session.contactReturnScreen = ChatScreen.MANAGING_CONTACTS
        pairedFromOpenChat = false
        scannedPeerId = null
        scannedPhoneHash = null
        scannedEmail = null
        session.state.update {
            it.copy(
                screen = ChatScreen.EDITING_CONTACT,
                selectedContactId = null,
                contactNameDraft = "",
                contactPhoneDraft = "",
                contactEmailDraft = "",
                contactGoogleEmailDraft = "",
                contactUsernameDraft = "",
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
        val contact = session.state.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        session.contactReturnScreen = if (session.state.value.screen == ChatScreen.CONTACT_PROFILE)
            ChatScreen.CONTACT_PROFILE else ChatScreen.MANAGING_CONTACTS
        session.state.update {
            it.copy(
                screen = ChatScreen.EDITING_CONTACT,
                selectedContactId = contact.id,
                contactNameDraft = contact.name,
                contactPhoneDraft = contact.phoneNumber,
                contactEmailDraft = contact.email,
                contactGoogleEmailDraft = contact.googleAccountEmail,
                contactUsernameDraft = contact.username,
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

    fun openCurrentChatProfile() {
        val state = session.state.value
        val peerId = state.selectedPeerId ?: return
        if (state.conversations.none { it.peerId == peerId && it.type == ConversationType.DIRECT }) return
        val contact = state.savedContacts.firstOrNull {
            it.linkedPeerId == peerId ||
                ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail) == peerId ||
                (it.cloudUserId.isNotBlank() && "account:${it.cloudUserId}" == peerId)
        }
        session.state.update { it.copy(screen = ChatScreen.CONTACT_PROFILE, selectedContactId = contact?.id) }
    }

    fun saveCurrentChatContact() {
        val state = session.state.value
        val peerId = state.selectedPeerId ?: return
        val name = state.conversations.firstOrNull {
            it.peerId == peerId && it.type == ConversationType.DIRECT
        }?.name ?: return
        beginAddContact()
        session.contactReturnScreen = ChatScreen.CONTACT_PROFILE
        scannedPeerId = peerId
        pairedFromOpenChat = true
        session.state.update { it.copy(contactNameDraft = name) }
    }

    fun closeCurrentChatProfile() {
        session.state.update { it.copy(screen = ChatScreen.CONVERSATION) }
    }

    fun closeContactEditor() {
        if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE)
            session.state.update { it.copy(screen = ChatScreen.CONTACT_PROFILE) }
        else beginManageContacts()
    }

    fun messageContact(contactId: String) {
        val contact = session.state.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        val peerId = contact.linkedPeerId ?: ContactIdentity.localPeerId(
            contact.phoneHash, contact.email, contact.googleAccountEmail,
        ) ?: contact.cloudUserId.takeIf(String::isNotBlank)?.let { "account:$it" }
        if (peerId == null && session.state.value.onlineAccountId.isBlank()) {
            session.state.update { it.copy(notice = "Sign in with Email or Google, or pair by QR/Nearby, to message this contact.") }
            return
        }
        session.workScope.launch {
            val account = if (session.state.value.onlineAccountId.isNotBlank() && session.cloudController != null)
                runCatching { session.accountLookup.resolveAccount(session.cloudController, contact, peerId.orEmpty(), promptForChoice = true,
                    openChatAfterChoice = true) }
                    .onFailure { session.showCloudError(it.localizedMessage ?: "Could not check this contact online.") }.getOrNull()
            else null
            if (session.state.value.accountCandidateContactId == contactId) return@launch
            val conversationId = contact.linkedPeerId ?: peerId ?: account?.let { "account:${it.uid}" } ?: return@launch
            session.store.savePeer(conversationId, contact.name, contact.phoneHash)
            session.persistence.reloadConversationsNow()
            session.navigation.openConversation(conversationId)
            if (account != null) session.cloudSync.syncPending()
        }
    }

    fun checkContactOnline(contactId: String) {
        val contact = session.state.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        val controller = session.cloudController
        if (session.state.value.onlineAccountId.isBlank() || controller == null) {
            session.showError("Sign in to check this contact online.")
            return
        }
        session.workScope.launch {
            val peerId = contact.linkedPeerId ?: ContactIdentity.localPeerId(
                contact.phoneHash, contact.email, contact.googleAccountEmail,
            ).orEmpty()
            runCatching { session.accountLookup.resolveAccount(controller, contact, peerId, promptForChoice = true) }
                .onSuccess { account ->
                    if (account != null) {
                        session.showNotice(if (contact.username.isNotBlank())
                            "Linked @${account.username} to ${contact.name}." else
                            "Found ${contact.name} online. Confirm phone-only matches by QR or Nearby.")
                        session.cloudSync.syncPending()
                    }
                }
                .onFailure { session.showCloudError(it.localizedMessage ?: "Could not check this contact online.") }
        }
    }

    fun selectOnlineAccount(uid: String) {
        val state = session.state.value
        val contact = state.savedContacts.firstOrNull { it.id == state.accountCandidateContactId } ?: return
        val account = state.accountCandidates.firstOrNull { it.uid == uid } ?: return
        val openChat = state.openChatAfterAccountChoice
        session.state.update { it.copy(accountCandidates = emptyList(), accountCandidateContactId = null,
            openChatAfterAccountChoice = false) }
        session.workScope.launch {
            session.accountLookup.linkOnlineAccount(contact, account)
            session.showNotice("Linked @${account.username} to ${contact.name}. Phone matches are not verified; confirm the username with them.")
            if (openChat) messageContact(contact.id)
            session.cloudSync.syncPending()
        }
    }

    fun cancelAccountSelection() {
        session.state.update { it.copy(accountCandidates = emptyList(), accountCandidateContactId = null,
            openChatAfterAccountChoice = false) }
    }

    fun updateContactDraft(profile: ContactProfile) {
        session.state.update {
            it.copy(
                contactNameDraft = profile.displayName.take(ChatLimits.MAX_NAME_LENGTH),
                contactPhoneDraft = profile.phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH),
                contactEmailDraft = profile.email.take(ChatLimits.MAX_EMAIL_LENGTH),
                contactGoogleEmailDraft = profile.googleAccountEmail.take(ChatLimits.MAX_EMAIL_LENGTH),
                contactUsernameDraft = profile.username.trim().removePrefix("@").take(20),
                contactBioDraft = profile.bio.take(ChatLimits.MAX_BIO_LENGTH),
                contactWebsiteDraft = profile.websiteUrl.take(ChatLimits.MAX_URL_LENGTH),
                contactInstagramDraft = profile.instagramUrl.take(ChatLimits.MAX_URL_LENGTH),
                contactXDraft = profile.xUrl.take(ChatLimits.MAX_URL_LENGTH),
                contactLinkedinDraft = profile.linkedinUrl.take(ChatLimits.MAX_URL_LENGTH),
                contactGithubDraft = profile.githubUrl.take(ChatLimits.MAX_URL_LENGTH),
            )
        }
    }

    fun importScannedContactCard(payload: String) {
        val card = ContactCardCodec.decodeCard(payload)
        if (card == null) {
            session.showError("That QR code is not a BLAP contact card.")
            return
        }
        beginAddContact()
        updateContactDraft(card.profile)
        scannedPeerId = card.peerId.takeIf(String::isNotBlank)
        scannedPhoneHash = PhoneIdentity.hash(card.profile.phoneNumber)
        scannedEmail = ContactIdentity.normalizeEmail(card.profile.email)
            ?: ContactIdentity.normalizeEmail(card.profile.googleAccountEmail)
        session.state.update { it.copy(contactSourceDraft = ContactSource.QR) }
    }

    fun updateContactName(name: String) {
        session.state.update { it.copy(contactNameDraft = name.take(ChatLimits.MAX_NAME_LENGTH)) }
    }

    fun updateContactPhone(phoneNumber: String) {
        session.state.update { it.copy(contactPhoneDraft = phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH)) }
    }

    fun saveContact() {
        val state = session.state.value
        val name = state.contactNameDraft.trim()
        val normalizedPhone = if (state.contactPhoneDraft.isBlank()) "" else
            PhoneIdentity.normalizeInternational(state.contactPhoneDraft)
        val email = if (state.contactEmailDraft.isBlank()) "" else
            ContactIdentity.normalizeEmail(state.contactEmailDraft)
        val googleEmail = if (state.contactGoogleEmailDraft.isBlank()) "" else
            ContactIdentity.normalizeEmail(state.contactGoogleEmailDraft)
        val username = state.contactUsernameDraft.trim().removePrefix("@").lowercase()
        if (name.isBlank() || normalizedPhone == null || email == null || googleEmail == null ||
            (username.isNotBlank() && !username.matches(Regex("[a-z0-9_]{3,20}"))) ||
            (normalizedPhone.isBlank() && email.isBlank() && googleEmail.isBlank() && username.isBlank() && scannedPeerId == null)
        ) {
            session.state.update { it.copy(error = "Enter a name and a valid username, phone, or email.") }
            return
        }
        val phoneHash = PhoneIdentity.hash(normalizedPhone).orEmpty()
        session.workScope.launch {
            val chatAccountUid = scannedPeerId?.takeIf { it.startsWith("account:") }
                ?.removePrefix("account:").orEmpty()
            val linkedPeerId = scannedPeerId?.takeUnless { it.startsWith("account:") }?.takeIf {
                pairedFromOpenChat ||
                    (phoneHash.isNotBlank() && scannedPhoneHash == phoneHash) ||
                    (scannedEmail != null && (scannedEmail == email || scannedEmail == googleEmail)) ||
                    (username.isNotBlank() && state.contactSourceDraft == ContactSource.QR) ||
                    (phoneHash.isBlank() && email.isBlank() && googleEmail.isBlank())
            } ?: if (username.isBlank()) session.store.getKnownContacts()
                .firstOrNull { phoneHash.isNotBlank() && it.phoneHash == phoneHash }?.peerId else null
            val existing = state.selectedContactId?.let { id ->
                state.savedContacts.firstOrNull { it.id == id }
            } ?: state.savedContacts.firstOrNull { username.isNotBlank() && it.username == username }
            val retainedPeerId = existing?.linkedPeerId?.takeIf {
                existing.username == username &&
                    ((phoneHash.isNotBlank() && existing.phoneHash == phoneHash) ||
                        (email.isNotBlank() && existing.email == email) ||
                        (googleEmail.isNotBlank() && existing.googleAccountEmail == googleEmail) ||
                        username.isNotBlank())
            }
            val sameAccount = existing != null &&
                (existing.username.isBlank() || existing.username == username) &&
                ((username.isNotBlank() && existing.username == username) ||
                    (phoneHash.isNotBlank() && existing.phoneHash == phoneHash) ||
                    (email.isNotBlank() && existing.email == email) ||
                    (googleEmail.isNotBlank() && existing.googleAccountEmail == googleEmail))
            val cloudUserId = chatAccountUid.ifBlank {
                existing?.cloudUserId?.takeIf { sameAccount }.orEmpty()
            }
            val savedId = existing?.id ?: UUID.randomUUID().toString()
            session.store.saveContact(
                SavedContact(
                    id = savedId,
                    name = name,
                    phoneNumber = normalizedPhone,
                    phoneHash = phoneHash,
                    linkedPeerId = linkedPeerId ?: retainedPeerId,
                    email = email,
                    googleAccountEmail = googleEmail,
                    cloudUserId = cloudUserId,
                    username = username,
                    bio = state.contactBioDraft.trim(),
                    websiteUrl = ProfileUrl.normalize(state.contactWebsiteDraft),
                    instagramUrl = ProfileUrl.normalize(state.contactInstagramDraft),
                    xUrl = ProfileUrl.normalize(state.contactXDraft),
                    linkedinUrl = ProfileUrl.normalize(state.contactLinkedinDraft),
                    githubUrl = ProfileUrl.normalize(state.contactGithubDraft),
                    source = state.contactSourceDraft,
                ),
            )
            session.persistence.reloadSavedContactsNow()
            val syntheticPeerId = ContactIdentity.localPeerId(phoneHash, email, googleEmail)
            val actualPeerId = linkedPeerId ?: retainedPeerId
            if (actualPeerId != null && syntheticPeerId != null) {
                session.store.moveConversation(syntheticPeerId, actualPeerId)
            }
            session.cloudSync.syncPending()
            session.persistence.reloadConversationsNow()
            session.state.update {
                it.copy(
                    screen = session.contactReturnScreen,
                    selectedContactId = if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE)
                        savedId else null,
                    contactNameDraft = "",
                    contactPhoneDraft = "",
                    contactUsernameDraft = "",
                )
            }
        }
    }

    fun deleteContact() {
        val contactId = session.state.value.selectedContactId ?: return
        session.workScope.launch {
            session.store.deleteContact(contactId)
            session.persistence.reloadSavedContactsNow()
            session.state.update {
                it.copy(screen = if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE)
                    ChatScreen.CONVERSATION else ChatScreen.MANAGING_CONTACTS, selectedContactId = null)
            }
        }
    }

    fun importDeviceContacts(contacts: List<DeviceContact>) {
        session.workScope.launch {
            val meshByHash = session.store.getKnownContacts()
                .filter { it.phoneHash.isNotBlank() }
                .associateBy(GroupMember::phoneHash)
            val existingContacts = session.store.getSavedContacts()
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
                session.store.saveContact(
                    SavedContact(
                        id = UUID.randomUUID().toString(),
                        name = contact.name.take(ChatLimits.MAX_NAME_LENGTH),
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
            session.persistence.reloadSavedContactsNow()
            session.state.update { it.copy(notice = if (imported == 0) "No new contacts to import." else
                "$imported contact${if (imported == 1) "" else "s"} imported.") }
        }
    }

    fun updateContactSearch(query: String) {
        session.state.update { it.copy(contactSearch = query.take(80)) }
    }

}
