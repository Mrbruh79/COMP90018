package com.example.blap.chat

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ContactAccountLookup(private val session: MessagingSession) {
    private val chatAccounts = ConcurrentHashMap<String, CloudAccount>()
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    fun cachedChatAccount(uid: String): CloudAccount? = chatAccounts[uid]

    /** Read the participant's public card by UID, never by their non-unique display name. */
    suspend fun loadChatAccount(peerId: String, refresh: Boolean = false): CloudAccount? {
        val localUid = session.state.value.onlineAccountId.takeIf(String::isNotBlank) ?: return null
        val controller = session.cloudController ?: return null
        val uid = if (peerId.startsWith("account:")) peerId.removePrefix("account:") else
            session.store.getSavedContacts().firstOrNull { peerId in ContactIdentity.aliases(it) }
                ?.cloudUserId?.takeIf(String::isNotBlank)
                ?: session.connectedPeers[peerId]?.takeIf { it.accountVerified }?.accountUid
                ?: return null
        if (uid.isBlank() || uid == localUid) return null
        return accountLocks.getOrPut(uid) { Mutex() }.withLock {
            val account = if (!refresh && chatAccounts.containsKey(uid)) chatAccounts[uid] else {
                try {
                    withTimeoutOrNull(7_000L) { controller.getAccount(uid) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null // Card lookup failure must not interrupt messaging or erase cached identity.
                }
            }
            if (session.state.value.onlineAccountId != localUid || account == null || account.uid != uid ||
                !account.username.matches(Regex("[a-z0-9_]{3,20}"))) return@withLock null
            chatAccounts[uid] = account
            session.store.getSavedContacts().filter { it.cloudUserId == uid && it.username != account.username }
                .forEach { session.store.saveContact(it.copy(username = account.username)) }
            session.persistence.reloadSavedContactsNow()
            if (session.store.getConversations().any { it.peerId == peerId }) {
                session.store.savePeerUsername(peerId, account.username)
                session.persistence.reloadConversationsNow()
            }
            account
        }
    }

    suspend fun findMatches(controller: CloudChatController, contact: SavedContact, peerId: String = ""): List<CloudAccount> {
        if (contact.cloudUserId.isNotBlank()) {
            return listOfNotNull(controller.getAccount(contact.cloudUserId))
        }
        if (contact.username.isNotBlank()) {
            return controller.findAccounts(username = contact.username).distinctBy(CloudAccount::uid)
        }
        val pairedPeer = contact.linkedPeerId ?: peerId.takeUnless {
            it.startsWith("phone:") || it.startsWith("email:") || it.startsWith("account:") || it.startsWith("contact:")
        }
        val accounts = mutableListOf<CloudAccount>()
        if (!pairedPeer.isNullOrBlank()) accounts += controller.findAccounts(peerId = pairedPeer)
        listOf(contact.email, contact.googleAccountEmail).filter(String::isNotBlank).distinct().forEach {
            accounts += controller.findAccounts(email = it)
        }
        if (contact.phoneNumber.isNotBlank()) accounts += controller.findAccounts(phoneNumber = contact.phoneNumber)
        return accounts.distinctBy(CloudAccount::uid).sortedBy(CloudAccount::username)
    }

    suspend fun resolveAccount(
        controller: CloudChatController,
        contact: SavedContact?,
        peerId: String,
        promptForChoice: Boolean = false,
        openChatAfterChoice: Boolean = false,
        refresh: Boolean = false,
    ): CloudAccount? {
        // A saved match is an account identity, not a search to repeat for every message.
        if (!refresh && !contact?.cloudUserId.isNullOrBlank()) {
            return CloudAccount(contact.cloudUserId, contact.name, contact.linkedPeerId.orEmpty(), contact.username)
        }
        if (!contact?.cloudUserId.isNullOrBlank()) {
            controller.getAccount(contact.cloudUserId)?.let { account ->
                linkOnlineAccount(contact, account)
                return account
            }
            session.showCloudError("This saved account is no longer available. Add their current account as a new contact.")
            return null
        }
        if (contact == null && peerId.startsWith("account:")) {
            return CloudAccount(peerId.removePrefix("account:"), "", "")
        }
        if (contact == null) {
            val senderUid = session.store.getMessages(peerId).firstOrNull {
                it.author == MessageAuthor.PEER && it.senderAccountId.isNotBlank()
            }?.senderAccountId
            if (senderUid != null) controller.getAccount(senderUid)?.let { return it }
        }
        if (!contact?.username.isNullOrBlank()) {
            val account = controller.findAccounts(username = contact.username).singleOrNull()
            if (account != null) {
                linkOnlineAccount(contact, account)
                return account
            }
            session.showCloudError("No account found for @${contact.username}. Check the username on their profile card.")
            return null
        }
        if (contact != null) {
            val candidates = findMatches(controller, contact, peerId)
            if (candidates.size > 1) {
                if (promptForChoice) session.state.update { it.copy(accountCandidates = candidates,
                    accountCandidateContactId = contact.id, openChatAfterAccountChoice = openChatAfterChoice) }
                else session.showCloudError("Several accounts match. Choose their username in Contacts > Find online.")
                return null
            }
            val account = candidates.singleOrNull()
            if (account == null) {
                session.showCloudError("No online account matched. Check their username or discovery settings.")
                return null
            }
            linkOnlineAccount(contact, account)
            if (contact.phoneNumber.isNotBlank()) session.showNotice("Linked @${account.username}. Phone matches are not verified; confirm the username with them.")
            return account
        }
        val account = peerId.takeUnless {
            it.startsWith("phone:") || it.startsWith("email:") || it.startsWith("contact:")
        }?.let { controller.findAccounts(peerId = it).singleOrNull() }
        if (account == null) session.showCloudError("No online account matched. Find their username in Contacts first.")
        return account
    }

    fun linkOnlineAccount(contact: SavedContact, account: CloudAccount) {
        if (account.peerId.isNotBlank() && account.nearbyPublicKey.isNotBlank()) {
            session.dependencies.nearbyIdentities.remember(TrustedNearbyIdentity(account.peerId, account.username,
                account.uid, account.nearbyPublicKey, accountVerified = true))
        }
        val duplicates = session.store.getSavedContacts().filter {
            it.id != contact.id && (it.cloudUserId == account.uid ||
                (it.cloudUserId.isBlank() && ((account.username.isNotBlank() && it.username == account.username) ||
                    (account.peerId.isNotBlank() && it.linkedPeerId == account.peerId))))
        }
        val combined = duplicates.fold(contact) { saved, duplicate ->
            saved.copy(
                phoneNumber = saved.phoneNumber.ifBlank { duplicate.phoneNumber },
                phoneHash = saved.phoneHash.ifBlank { duplicate.phoneHash },
                email = saved.email.ifBlank { duplicate.email },
                googleAccountEmail = saved.googleAccountEmail.ifBlank { duplicate.googleAccountEmail },
                bio = saved.bio.ifBlank { duplicate.bio },
                websiteUrl = saved.websiteUrl.ifBlank { duplicate.websiteUrl },
                instagramUrl = saved.instagramUrl.ifBlank { duplicate.instagramUrl },
                xUrl = saved.xUrl.ifBlank { duplicate.xUrl },
                linkedinUrl = saved.linkedinUrl.ifBlank { duplicate.linkedinUrl },
                githubUrl = saved.githubUrl.ifBlank { duplicate.githubUrl },
            )
        }
        val linked = combined.copy(cloudUserId = account.uid,
            username = account.username.ifBlank { contact.username },
            linkedPeerId = contact.linkedPeerId?.takeIf { session.connectedPeers.containsKey(it) }
                ?: account.peerId.takeIf(String::isNotBlank) ?: contact.linkedPeerId)
        session.store.saveContact(linked)
        session.persistence.mergeContactConversations(linked, ContactIdentity.aliases(contact) +
            duplicates.flatMap { ContactIdentity.aliases(it) })
        duplicates.forEach { session.store.deleteContact(it.id) }
        session.persistence.reloadSavedContactsNow()
        session.persistence.reloadConversationsNow()
        session.state.value.selectedPeerId?.let { session.persistence.reloadMessagesNow(it) }
    }

    suspend fun linkNearbyPeer(peer: ConnectedPeer): ConnectedPeer {
        val contacts = session.store.getSavedContacts()
        val saved = contacts.firstOrNull { peer.accountVerified && peer.accountUid.isNotBlank() && it.cloudUserId == peer.accountUid }
            ?: contacts.firstOrNull { it.linkedPeerId == peer.peerId &&
                (it.cloudUserId.isBlank() || !peer.accountVerified || it.cloudUserId == peer.accountUid) }
            ?: contacts.firstOrNull { peer.username.isNotBlank() && it.username.equals(peer.username, ignoreCase = true) &&
                (it.cloudUserId.isBlank() || it.cloudUserId == peer.accountUid) }
        var account = if (peer.accountVerified && peer.accountUid.isNotBlank())
            CloudAccount(peer.accountUid, peer.name, peer.peerId, peer.username, peer.publicKey) else null
        if (account == null && peer.username.isNotBlank() && session.state.value.onlineAccountId.isNotBlank()) {
            val controller = session.cloudController
            account = runCatching {
                val candidate = if (!saved?.cloudUserId.isNullOrBlank()) controller?.getAccount(saved.cloudUserId)
                    else controller?.findAccounts(username = peer.username)?.singleOrNull()
                candidate?.takeIf { it.uid == peer.accountUid && it.username == peer.username &&
                    it.peerId == peer.peerId && it.nearbyPublicKey == peer.publicKey && peer.publicKey.isNotBlank() }
            }.getOrNull()
            currentCoroutineContext().ensureActive()
        }
        if (saved != null && account != null) {
            linkOnlineAccount(saved.copy(linkedPeerId = peer.peerId), account)
        } else if (saved != null && saved.cloudUserId.isBlank()) {
            val linked = saved.copy(linkedPeerId = peer.peerId, username = peer.username.ifBlank { saved.username })
            session.store.saveContact(linked)
            session.persistence.mergeContactConversations(linked, setOf(peer.peerId))
        }
        return if (account != null) peer.copy(accountVerified = true) else peer
    }

}
