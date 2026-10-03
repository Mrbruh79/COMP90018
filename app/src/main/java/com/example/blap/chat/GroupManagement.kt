package com.example.blap.chat

import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class GroupManagement(private val session: MessagingSession) {

    fun saveGroupSettings() {
        val state = session.state.value
        val groupId = state.selectedPeerId ?: return
        session.workScope.launch {
            val current = session.store.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            if (current.ownerId != session.localPeerId) {
                session.showError("Only the group owner can change members or the group name.")
                return@launch
            }
            val name = state.groupNameDraft.trim()
            if (name.isBlank()) {
                session.showError("Enter a group name.")
                return@launch
            }
            val selected = state.groupContacts.filter { it.peerId in state.selectedGroupMemberIds }
            if (selected.size > 7) {
                session.showError("Private groups can have up to eight members, including you.")
                return@launch
            }
            val group = current.copy(
                name = name,
                createdAt = System.currentTimeMillis(),
                cloudSynced = false,
                members = listOf(GroupMember(session.localPeerId, state.displayName, session.localPhoneHash)) +
                    selected.map { GroupMember(it.peerId, it.name, it.phoneHash) },
            )
            session.store.saveGroup(group)
            session.nearbyTransport.publishGroup(group)
            session.workScope.launch { session.cloudSync.uploadGroup(group) }
            session.persistence.reloadConversationsNow()
            session.state.update { it.copy(screen = ChatScreen.CONVERSATION) }
        }
    }

    fun deleteCurrentGroup() {
        val groupId = session.state.value.selectedPeerId ?: return
        session.workScope.launch {
            val group = session.store.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            if (group.ownerId != session.localPeerId) {
                session.showError("Only the group owner can delete this group from the mesh.")
                return@launch
            }
            if (group.cloudSynced && group.ownerAccountId.isNotBlank() &&
                group.ownerAccountId == session.state.value.onlineAccountId) {
                session.workScope.launch {
                    runCatching { session.cloudController?.deleteGroup(groupId) }
                        .onFailure { session.showCloudError(it.localizedMessage ?: "Could not remove the online group.") }
                }
            }
            session.store.deleteGroup(groupId)
            session.persistence.reloadConversationsNow()
            session.navigation.showConversationList()
        }
    }

    fun createPrivateGroup() {
        val state = session.state.value
        val name = state.groupNameDraft.trim()
        val selectedContacts = state.groupContacts.filter {
            it.peerId in state.selectedGroupMemberIds
        }
        if (name.isBlank() || selectedContacts.isEmpty()) {
            session.state.update { it.copy(error = "Enter a group name and choose at least one contact.") }
            return
        }
        if (selectedContacts.size > 7) {
            session.showError("Private groups can have up to eight members, including you.")
            return
        }

        val group = PrivateGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            ownerId = session.localPeerId,
            createdAt = System.currentTimeMillis(),
            members = listOf(GroupMember(session.localPeerId, state.displayName, session.localPhoneHash)) + selectedContacts.map {
                GroupMember(it.peerId, it.name, it.phoneHash)
            },
            ownerAccountId = state.onlineAccountId,
        )
        session.workScope.launch {
            session.store.saveGroup(group)
            session.nearbyTransport.publishGroup(group)
            session.workScope.launch { session.cloudSync.uploadGroup(group) }
            session.persistence.reloadConversationsNow()
            session.state.update {
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

    fun onGroupReceived(group: PrivateGroup) {
        if (group.id == MeshGroup.ID) return
        if (group.members.none {
                it.peerId == session.localPeerId || (session.localPhoneHash.isNotBlank() && it.phoneHash == session.localPhoneHash)
            }
        ) return
        session.workScope.launch {
            session.store.saveGroup(group)
            session.persistence.reloadConversationsNow()
        }
    }

    fun onPrivateGroup(group: CloudPrivateGroup) {
        session.workScope.launch {
            val localUid = session.state.value.onlineAccountId
            if (group.members.none { it.uid == localUid }) return@launch
            val contacts = session.store.getSavedContacts().filter { it.cloudUserId.isNotBlank() }
                .associateBy(SavedContact::cloudUserId)
            val members = group.members.map { member ->
                val contact = contacts[member.uid]
                GroupMember(
                    peerId = if (member.uid == localUid) session.localPeerId else
                        contact?.linkedPeerId ?: contact?.let {
                            ContactIdentity.localPeerId(it.phoneHash, it.email, it.googleAccountEmail)
                        } ?: member.peerId.ifBlank { "account:${member.uid}" },
                    name = contact?.name ?: member.name.ifBlank { "Online contact" },
                    phoneHash = contact?.phoneHash.orEmpty(),
                )
            }
            val ownerId = members.getOrNull(group.members.indexOfFirst { it.uid == group.ownerUid })?.peerId
                ?: return@launch
            session.store.saveGroup(PrivateGroup(group.id, group.name, ownerId, group.revision, members,
                cloudSynced = true, ownerAccountId = group.ownerUid))
            session.persistence.reloadConversationsNow()
            session.cloudSync.syncPending()
        }
    }

    fun synchronizeGroupsWith(peerId: String) {
        val groups = session.store.getGroups()
        val conversationIds = groups.filter { it.id != MeshGroup.ID }.map(PrivateGroup::id)
        val messages = conversationIds.flatMap { conversationId ->
            session.store.getMessages(conversationId).map { message ->
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
        session.nearbyTransport.synchronizeGroups(peerId, groups, messages)
    }

    fun reloadGroupContactsNow(force: Boolean = false) {
        val knownContacts = session.store.getKnownContacts().associateBy(GroupMember::peerId).toMutableMap()
        session.connectedPeers.values.forEach { peer ->
            knownContacts[peer.peerId] = GroupMember(peer.peerId, peer.name, peer.phoneHash)
        }
        val contactsByIdentity = linkedMapOf<String, GroupContact>()
        knownContacts.values.forEach { contact ->
            if (contact.peerId.startsWith("account:") || contact.peerId.startsWith("phone:") ||
                contact.peerId.startsWith("email:")) return@forEach
            val key = "peer:${contact.peerId}"
            contactsByIdentity[key] = GroupContact(
                peerId = contact.peerId,
                name = contact.name,
                connected = session.connectedPeers.containsKey(contact.peerId),
                phoneHash = contact.phoneHash,
                availableOnMesh = true,
            )
        }
        session.store.getSavedContacts().forEach { contact ->
            val linkedPeerId = contact.linkedPeerId
                ?: knownContacts.values.firstOrNull {
                    contact.phoneHash.isNotBlank() && it.phoneHash == contact.phoneHash
                }?.peerId
            linkedPeerId?.let { contactsByIdentity.remove("peer:$it") }
            val contactKey = contact.cloudUserId.takeIf(String::isNotBlank)?.let { "account:$it" } ?: "contact:${contact.id}"
            contactsByIdentity[contactKey] = GroupContact(
                peerId = linkedPeerId ?: if (contact.phoneHash.isNotBlank()) "phone:${contact.phoneHash}"
                    else contact.cloudUserId.takeIf(String::isNotBlank)?.let { "account:$it" }
                        ?: "contact:${contact.id}",
                name = contact.name,
                connected = linkedPeerId?.let(session.connectedPeers::containsKey) == true,
                phoneNumber = contact.phoneNumber,
                phoneHash = contact.phoneHash,
                email = contact.email.ifBlank { contact.googleAccountEmail },
                availableOnMesh = linkedPeerId != null,
                username = contact.username,
            )
        }
        val contacts = contactsByIdentity.values.map { contact ->
            GroupContact(
                peerId = contact.peerId,
                name = contact.name,
                connected = contact.connected,
                phoneNumber = contact.phoneNumber,
                phoneHash = contact.phoneHash,
                email = contact.email,
                availableOnMesh = contact.availableOnMesh,
                username = contact.username,
            )
        }.sortedBy { it.name.lowercase() }
        session.state.update { state ->
            if (force || state.screen == ChatScreen.CREATING_GROUP ||
                state.screen == ChatScreen.GROUP_SETTINGS
            ) {
                state.copy(groupContacts = contacts)
            } else {
                state
            }
        }
    }

}
