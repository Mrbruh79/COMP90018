package com.example.blap.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class CloudChatSynchronizer(private val session: MessagingSession) : CloudChatController.Listener {
    private val cloudUploadsInFlight = ConcurrentHashMap.newKeySet<String>()
    fun start() {
        if (session.state.value.onlineAccountId.isNotBlank()) {
            session.cloudController?.start(session.state.value.onlineAccountId, this)
            syncPending()
        }
    }
    fun stop() = session.cloudController?.stop()
    override fun onPrivateGroup(group: CloudPrivateGroup) = session.groupManagement.onPrivateGroup(group)

    fun accountChanged(accountId: String) {
        val previous = session.state.value.onlineAccountId
        if (previous != accountId) {
            session.cloudController?.stop()
            session.state.update { it.copy(onlineAccountId = accountId) }
            if (accountId.isNotBlank()) session.cloudController?.start(accountId, this)
        }
        if (accountId.isNotBlank()) session.workScope.launch {
            session.accountPublisher.publishAccount()
            syncPending()
        }
    }

    override fun onDirectMessage(otherUid: String, message: CloudChatMessage) {
        session.workScope.launch {
            val contact = session.store.getSavedContacts().firstOrNull { it.cloudUserId == otherUid }
            val peerId = contact?.linkedPeerId ?: contact?.let {
                ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail)
            } ?: message.senderPeerId.takeIf(String::isNotBlank) ?: "account:$otherUid"
            session.store.savePeer(peerId, contact?.name ?: message.senderName.ifBlank { "Online contact" },
                contact?.phoneHash.orEmpty())
            val inserted = session.store.saveMessage(
                message.toLocalMessage(peerId, session.state.value.onlineAccountId),
            )
            if (inserted) {
                session.persistence.reloadSavedContactsNow()
                session.persistence.reloadConversationsNow()
                if (session.state.value.selectedPeerId == peerId) session.persistence.reloadMessagesNow(peerId)
                session.persistence.notifyIncoming(message.toLocalMessage(peerId, session.state.value.onlineAccountId))
            }
        }
    }

    override fun onGroupMessage(groupId: String, message: CloudChatMessage) {
        session.workScope.launch {
            val inserted = session.store.saveMessage(message.toLocalMessage(groupId, session.state.value.onlineAccountId))
            if (inserted) {
                session.persistence.reloadConversationsNow()
                if (session.state.value.selectedPeerId == groupId) session.persistence.reloadMessagesNow(groupId)
                session.persistence.notifyIncoming(message.toLocalMessage(groupId, session.state.value.onlineAccountId))
            }
        }
    }

    override fun onCloudError(message: String) {
        session.state.update { it.copy(notice = "Online chat: $message") }
    }

    fun CloudChatMessage.toLocalMessage(conversationId: String, localUid: String): ChatMessage =
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

    fun syncPending() {
        val uid = session.state.value.onlineAccountId
        if (uid.isBlank() || session.cloudController == null) return
        session.store.getCloudPendingGroups().filter {
            it.ownerId == session.localPeerId && it.ownerAccountId == uid
        }.forEach { group ->
            session.workScope.launch { uploadGroup(group) }
        }
        session.store.getCloudPendingMessages().filter { it.senderAccountId == uid }.forEach { message ->
            session.workScope.launch { uploadMessage(message) }
        }
    }

    suspend fun uploadGroup(group: PrivateGroup): Boolean {
        val controller = session.cloudController ?: return false
        val localUid = session.state.value.onlineAccountId
        if (localUid.isBlank() || group.ownerId != session.localPeerId || group.ownerAccountId != localUid) return false
        if (group.members.none { it.peerId == session.localPeerId }) return false
        val contacts = session.store.getSavedContacts()
        val members = group.members.map { member ->
            if (member.peerId == session.localPeerId) CloudGroupMember(localUid, session.localPeerId, member.name)
            else {
                val contact = contacts.firstOrNull {
                    it.linkedPeerId == member.peerId ||
                        (it.cloudUserId.isNotBlank() && "account:${it.cloudUserId}" == member.peerId) ||
                        "contact:${it.id}" == member.peerId ||
                        ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail) == member.peerId ||
                        (member.phoneHash.isNotBlank() && it.phoneHash == member.phoneHash)
                }
                val account = session.accountLookup.resolveAccount(controller, contact, member.peerId) ?: return false
                CloudGroupMember(account.uid, account.peerId.ifBlank { "account:${account.uid}" }, member.name)
            }
        }
        return runCatching {
            controller.saveGroup(CloudPrivateGroup(group.id, group.name, localUid, group.createdAt, members))
            session.store.markGroupCloudSynced(group.id, group.createdAt)
            true
        }.getOrElse {
            onCloudError(it.localizedMessage ?: "Could not upload the private group.")
            false
        }
    }

    suspend fun uploadMessage(message: ChatMessage) {
        val controller = session.cloudController ?: return
        val localUid = session.state.value.onlineAccountId
        if (localUid.isBlank() || message.senderAccountId != localUid ||
            message.cloudSynced || !cloudUploadsInFlight.add(message.id)
        ) return
        try {
            val cloudMessage = CloudChatMessage(
                message.id, localUid, session.localPeerId, session.state.value.displayName,
                message.text, message.sentAt,
            )
            val group = session.store.getGroups().firstOrNull { it.id == message.peerId }
            if (group != null) {
                if (group.ownerId == session.localPeerId && !group.cloudSynced && !uploadGroup(group)) return
                controller.sendGroup(group.id, cloudMessage)
            } else if (message.peerId != MeshGroup.ID) {
                val contact = session.store.getSavedContacts().firstOrNull {
                    it.linkedPeerId == message.peerId ||
                        (it.cloudUserId.isNotBlank() && "account:${it.cloudUserId}" == message.peerId) ||
                        ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail) == message.peerId
                }
                val account = session.accountLookup.resolveAccount(controller, contact, message.peerId) ?: return
                controller.sendDirect(account.uid, cloudMessage)
            } else return
            session.store.markCloudSynced(message.id)
            session.persistence.setMessageStatus(message.peerId, message.id, MessageStatus.SENT)
        } catch (error: Exception) {
            onCloudError(error.localizedMessage ?: "A message will retry when online.")
        } finally {
            cloudUploadsInFlight.remove(message.id)
        }
    }

}
