package com.example.blap.chat

import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class ContactsViewModel(private val session: MessagingSession) : androidx.lifecycle.ViewModel() {
    val uiState = session.uiState
    private var pendingContact: SavedContact? = null
    private var editVersion = 0

    private fun resetLookup() {
        editVersion++
        pendingContact = null
        session.state.update { it.copy(savingContact = false, accountCandidates = emptyList(),
            accountCandidateContactId = null, openChatAfterAccountChoice = false) }
    }
    private var scannedPeerId: String? = null
    private var pairedFromOpenChat = false

    fun beginManageContacts() {
        resetLookup()
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
        resetLookup()
        session.contactReturnScreen = ChatScreen.MANAGING_CONTACTS
        pairedFromOpenChat = false
        scannedPeerId = null
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
        resetLookup()
        scannedPeerId = null
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
        session.workScope.launch { session.accountLookup.loadChatAccount(peerId, refresh = true) }
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
        val peer = session.connectedPeers[session.persistence.transportPeerId(peerId)]
        val cachedUsername = state.conversations.firstOrNull { it.peerId == peerId }?.username.orEmpty()
        session.state.update { it.copy(contactNameDraft = name,
            contactUsernameDraft = cachedUsername.ifBlank { peer?.username.orEmpty() }) }
        val version = editVersion
        session.workScope.launch {
            val account = session.accountLookup.loadChatAccount(peerId) ?: return@launch
            if (version != editVersion) return@launch
            session.state.update {
                if (it.screen == ChatScreen.EDITING_CONTACT && it.selectedPeerId == peerId &&
                    !it.savingContact && it.contactUsernameDraft.isBlank())
                    it.copy(contactUsernameDraft = account.username) else it
            }
        }
    }

    fun closeCurrentChatProfile() {
        session.state.update { it.copy(screen = ChatScreen.CONVERSATION) }
    }

    fun closeContactEditor() {
        resetLookup()
        if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE)
            session.state.update { it.copy(screen = ChatScreen.CONTACT_PROFILE) }
        else beginManageContacts()
    }

    fun messageContact(contactId: String) {
        val contact = session.state.value.savedContacts.firstOrNull { it.id == contactId } ?: return
        val peerId = ContactIdentity.conversationId(contact)
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
            val conversationId = account?.let { "account:${it.uid}" } ?: peerId ?: return@launch
            session.store.savePeer(conversationId, contact.name, contact.phoneHash)
            session.store.reopenConversation(conversationId)
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
            runCatching { session.accountLookup.resolveAccount(controller, contact, peerId, promptForChoice = true, refresh = true) }
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
        val draft = pendingContact
        if (draft != null) {
            val chosen = state.accountCandidates.firstOrNull { it.uid == uid } ?: return
            val controller = session.cloudController ?: return
            val version = editVersion
            session.state.update { it.copy(accountCandidates = emptyList(), savingContact = true) }
            session.workScope.launch {
                try {
                    val account = controller.getAccount(chosen.uid)
                    if (version != editVersion || session.state.value.screen != ChatScreen.EDITING_CONTACT) return@launch
                    if (account == null || account.username != chosen.username)
                        session.showError("That account changed. Look up the contact again before saving.")
                    else finishSaveContact(draft, account)
                } catch (error: kotlinx.coroutines.CancellationException) { throw error
                } catch (error: Exception) {
                    if (version == editVersion) session.showError("Contact not saved. Could not confirm the account. Try again.")
                } finally {
                    if (version == editVersion) {
                        pendingContact = null
                        session.state.update { it.copy(savingContact = false) }
                    }
                }
            }
            return
        }
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
        pendingContact = null
        session.state.update { it.copy(accountCandidates = emptyList(), accountCandidateContactId = null,
            openChatAfterAccountChoice = false) }
    }

    fun updateContactDraft(profile: ContactProfile) {
        if (session.state.value.savingContact) return
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
        if (state.savingContact || state.accountCandidates.isNotEmpty()) return
        val name = state.contactNameDraft.trim()
        val phone = if (state.contactPhoneDraft.isBlank()) "" else PhoneIdentity.normalizeInternational(state.contactPhoneDraft)
        val email = if (state.contactEmailDraft.isBlank()) "" else ContactIdentity.normalizeEmail(state.contactEmailDraft)
        val googleEmail = if (state.contactGoogleEmailDraft.isBlank()) "" else ContactIdentity.normalizeEmail(state.contactGoogleEmailDraft)
        val username = state.contactUsernameDraft.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
        if (name.isBlank() || phone == null || email == null || googleEmail == null ||
            (username.isNotBlank() && !username.matches(Regex("[a-z0-9_]{3,20}"))) ||
            (phone.isBlank() && email.isBlank() && googleEmail.isBlank() && username.isBlank() && scannedPeerId == null)) {
            session.showError("Enter a name and a valid username, phone, or email.")
            return
        }
        val selected = session.store.getSavedContacts().firstOrNull { it.id == state.selectedContactId }
        val draft = SavedContact(
            id = selected?.id ?: UUID.randomUUID().toString(), name = name, phoneNumber = phone,
            phoneHash = PhoneIdentity.hash(phone).orEmpty(),
            linkedPeerId = scannedPeerId?.takeUnless { it.startsWith("account:") }
                ?: selected?.linkedPeerId?.takeIf {
                    selected.username == username && (selected.cloudUserId.isNotBlank() ||
                        (phone.isNotBlank() && selected.phoneNumber == phone) ||
                        (email.isNotBlank() && selected.email == email) ||
                        (googleEmail.isNotBlank() && selected.googleAccountEmail == googleEmail) ||
                        (phone.isBlank() && email.isBlank() && googleEmail.isBlank()))
                },
            cloudUserId = scannedPeerId?.takeIf { it.startsWith("account:") }?.removePrefix("account:")
                ?: selected?.cloudUserId?.takeIf { selected.username == username }.orEmpty(),
            email = email, googleAccountEmail = googleEmail, username = username,
            bio = state.contactBioDraft.trim(), websiteUrl = ProfileUrl.normalize(state.contactWebsiteDraft),
            instagramUrl = ProfileUrl.normalize(state.contactInstagramDraft), xUrl = ProfileUrl.normalize(state.contactXDraft),
            linkedinUrl = ProfileUrl.normalize(state.contactLinkedinDraft), githubUrl = ProfileUrl.normalize(state.contactGithubDraft),
            source = state.contactSourceDraft,
        )
        val version = editVersion
        session.state.update { it.copy(savingContact = true, error = null) }
        session.workScope.launch {
            try {
                val requiresOnline = (draft.source != ContactSource.QR && !pairedFromOpenChat) ||
                    draft.linkedPeerId == null
                val controller = session.cloudController
                val knownChatUsername = session.store.getConversations().firstOrNull {
                    it.peerId == "account:${draft.cloudUserId}"
                }?.username.orEmpty()
                val knownChatAccount = if (pairedFromOpenChat && draft.cloudUserId.isNotBlank() &&
                    draft.username.isNotBlank() && draft.username == knownChatUsername)
                    session.accountLookup.cachedChatAccount(draft.cloudUserId)
                        ?: CloudAccount(draft.cloudUserId, draft.name, "", draft.username) else null
                val matches = if (knownChatAccount != null) listOf(knownChatAccount)
                else if (session.state.value.onlineAccountId.isNotBlank() && controller != null)
                    session.accountLookup.findMatches(controller, draft)
                        .filter { it.username.matches(Regex("[a-z0-9_]{3,20}")) }
                else emptyList()
                if (version != editVersion || session.state.value.screen != ChatScreen.EDITING_CONTACT) return@launch
                when {
                    matches.size > 1 -> {
                        pendingContact = draft
                        session.state.update { it.copy(accountCandidates = matches, accountCandidateContactId = null,
                            openChatAfterAccountChoice = false) }
                    }
                    matches.size == 1 -> finishSaveContact(draft, matches.single())
                    !requiresOnline -> finishSaveContact(draft, null)
                    else -> session.showError(if (session.state.value.onlineAccountId.isBlank())
                        "Sign in and connect to the internet before adding an online contact."
                        else "No online account matched. Contact not saved. Check their username or discovery settings.")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == editVersion && session.state.value.screen == ChatScreen.EDITING_CONTACT) {
                    if (draft.linkedPeerId != null && (draft.source == ContactSource.QR || pairedFromOpenChat))
                        finishSaveContact(draft, null)
                    else session.showError("Contact not saved. " + (error.localizedMessage ?: "Could not look up the account. Try again."))
                }
            } finally {
                if (version == editVersion) session.state.update { it.copy(savingContact = false) }
            }
        }
    }

    private fun finishSaveContact(draft: SavedContact, account: CloudAccount?) {
        val existing = if (account == null) null else session.store.getSavedContacts().firstOrNull {
            it.cloudUserId == account.uid || (it.cloudUserId.isBlank() && account.username.isNotBlank() && it.username == account.username)
        }
        val saved = if (existing != null && draft.id != existing.id) draft.copy(
            id = existing.id, phoneNumber = draft.phoneNumber.ifBlank { existing.phoneNumber },
            phoneHash = draft.phoneHash.ifBlank { existing.phoneHash },
            email = draft.email.ifBlank { existing.email },
            googleAccountEmail = draft.googleAccountEmail.ifBlank { existing.googleAccountEmail },
            bio = draft.bio.ifBlank { existing.bio }, websiteUrl = draft.websiteUrl.ifBlank { existing.websiteUrl },
            instagramUrl = draft.instagramUrl.ifBlank { existing.instagramUrl }, xUrl = draft.xUrl.ifBlank { existing.xUrl },
            linkedinUrl = draft.linkedinUrl.ifBlank { existing.linkedinUrl }, githubUrl = draft.githubUrl.ifBlank { existing.githubUrl },
        ) else draft
        if (account == null) session.store.saveContact(saved)
        else session.accountLookup.linkOnlineAccount(saved, account)
        if (existing != null && draft.id != existing.id) session.store.deleteContact(draft.id)
        pendingContact = null
        session.persistence.reloadSavedContactsNow()
        session.persistence.reloadConversationsNow()
        session.state.update {
            it.copy(screen = session.contactReturnScreen,
                selectedContactId = if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE) saved.id else null,
                accountCandidates = emptyList(), accountCandidateContactId = null, openChatAfterAccountChoice = false,
                savingContact = false, contactNameDraft = "", contactPhoneDraft = "", contactUsernameDraft = "",
                notice = if (account == null) "Nearby contact saved." else "Saved @${account.username}.",
                error = null)
        }
        session.cloudSync.syncPending()
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
