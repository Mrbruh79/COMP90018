package com.example.blap.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class GroupsViewModel(private val session: MessagingSession) : androidx.lifecycle.ViewModel() {
    val uiState = session.uiState
    fun saveGroupSettings() = session.groupManagement.saveGroupSettings()
    fun deleteCurrentGroup() = session.groupManagement.deleteCurrentGroup()
    fun createPrivateGroup() = session.groupManagement.createPrivateGroup()

    fun beginCreateGroup() {
        session.groupReturnScreen = session.state.value.screen
        session.state.update {
            it.copy(
                screen = ChatScreen.CREATING_GROUP,
                groupNameDraft = "",
                selectedGroupMemberIds = emptySet(),
                error = null,
            )
        }
        session.workScope.launch { session.groupManagement.reloadGroupContactsNow() }
    }

    fun beginGroupSettings() {
        val groupId = session.state.value.selectedPeerId ?: return
        session.workScope.launch {
            val group = session.store.getGroups().firstOrNull { it.id == groupId } ?: return@launch
            session.groupManagement.reloadGroupContactsNow(force = true)
            session.state.update {
                val candidates = it.groupContacts.toMutableList()
                group.members.filterNot { member -> member.peerId == session.localPeerId }.forEach { member ->
                    if (candidates.none { contact -> contact.peerId == member.peerId }) {
                        candidates += GroupContact(member.peerId, member.name, false, phoneHash = member.phoneHash)
                    }
                }
                it.copy(
                    screen = ChatScreen.GROUP_SETTINGS,
                    canEditGroup = group.ownerId == session.localPeerId,
                    groupContacts = candidates,
                    groupNameDraft = group.name,
                    selectedGroupMemberIds = group.members
                        .filterNot { member -> member.peerId == session.localPeerId }
                        .map(GroupMember::peerId)
                        .toSet(),
                    error = null,
                )
            }
        }
    }

    fun updateGroupName(name: String) {
        session.state.update { it.copy(groupNameDraft = name.take(ChatLimits.MAX_GROUP_NAME_LENGTH)) }
    }

    fun toggleGroupMember(peerId: String) {
        if (session.state.value.groupContacts.none { it.peerId == peerId }) return
        session.state.update { state ->
            val selected = state.selectedGroupMemberIds.toMutableSet()
            if (!selected.add(peerId)) selected.remove(peerId)
            state.copy(selectedGroupMemberIds = selected)
        }
    }

}
