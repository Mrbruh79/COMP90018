package com.example.blap.chat

import kotlinx.coroutines.flow.update

class ContactAccountLookup(private val session: MessagingSession) {

    suspend fun resolveAccount(
        controller: CloudChatController,
        contact: SavedContact?,
        peerId: String,
        promptForChoice: Boolean = false,
        openChatAfterChoice: Boolean = false,
    ): CloudAccount? {
        if (!contact?.cloudUserId.isNullOrBlank()) {
            controller.getAccount(contact.cloudUserId)?.takeIf {
                contact.username.isBlank() || it.username == contact.username
            }?.let { account ->
                linkOnlineAccount(contact, account)
                return account
            }
        }
        if (contact == null && peerId.startsWith("account:")) {
            return controller.getAccount(peerId.removePrefix("account:"))
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
        val pairedPeerId = contact?.linkedPeerId ?: peerId.takeUnless {
            it.startsWith("phone:") || it.startsWith("email:") || it.startsWith("account:") ||
                it.startsWith("contact:")
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
                if (promptForChoice && contact != null) {
                    session.state.update { it.copy(accountCandidates = candidates.sortedBy(CloudAccount::username),
                        accountCandidateContactId = contact.id, openChatAfterAccountChoice = openChatAfterChoice) }
                } else session.showCloudError("Several accounts match this contact. Choose a username in Contacts > Find online.")
                return null
            }
            if (candidates.size == 1) {
                account = candidates.single()
                matchedByPhone = type == "phone"
                break
            }
        }
        if (account == null) {
            session.showCloudError("No online account matched. Ask for their @username or QR card, or check that phone lookup is enabled.")
            return null
        }
        if (contact != null) linkOnlineAccount(contact, account)
        if (matchedByPhone) {
            session.showCloudError("This number match is not verified. Confirm the person with their QR card.")
        }
        return account
    }

    fun linkOnlineAccount(contact: SavedContact, account: CloudAccount) {
        if (contact.cloudUserId == account.uid && contact.username == account.username) return
        session.store.saveContact(contact.copy(cloudUserId = account.uid,
            username = account.username.ifBlank { contact.username }))
        session.persistence.reloadSavedContactsNow()
        session.persistence.reloadConversationsNow()
    }

}
