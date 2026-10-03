package com.example.blap.chat

import kotlinx.coroutines.flow.update

/** Screen navigation does not start discovery or own any repository. */
class MessagingNavigation(private val session: MessagingSession) {
    fun openConversation(peerId: String) {
        if (session.state.value.conversations.none { it.peerId == peerId }) return
        session.state.update {
            it.copy(
                screen = ChatScreen.CONVERSATION,
                selectedPeerId = peerId,
                messages = emptyList(),
                error = null,
            )
        }
        session.persistence.reloadMessages(peerId)
    }

    fun showConversationList() {
        session.requestedEndpointId = null
        session.state.update {
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


    private fun manageContacts() {
        session.contactReturnScreen = ChatScreen.MANAGING_CONTACTS
        session.state.update { it.copy(screen = ChatScreen.MANAGING_CONTACTS,
            selectedContactId = null, contactNameDraft = "", contactPhoneDraft = "", error = null) }
        session.persistence.reloadSavedContactsNow()
    }

    fun showNotificationSettings() {
        session.state.update { it.copy(screen = ChatScreen.NOTIFICATION_SETTINGS, error = null) }
    }

    fun handleBack() {
        when (session.state.value.screen) {
            ChatScreen.CONVERSATION, ChatScreen.CONNECTING -> showConversationList()
            ChatScreen.CREATING_GROUP -> if (session.groupReturnScreen == ChatScreen.MANAGING_CONTACTS)
                manageContacts() else showConversationList()
            ChatScreen.MANAGING_CONTACTS, ChatScreen.SHOWING_MY_CARD, ChatScreen.SETTINGS -> showConversationList()
            ChatScreen.CONTACT_PROFILE, ChatScreen.GROUP_SETTINGS ->
                session.state.update { it.copy(screen = ChatScreen.CONVERSATION) }
            ChatScreen.EDITING_CONTACT -> if (session.contactReturnScreen == ChatScreen.CONTACT_PROFILE)
                session.state.update { it.copy(screen = ChatScreen.CONTACT_PROFILE) } else manageContacts()
            ChatScreen.EDITING_PROFILE ->
                session.state.update { it.copy(screen = session.profileReturnScreen, profileDraft = null, error = null) }
            ChatScreen.DISCOVERY_SETTINGS ->
                session.state.update { it.copy(screen = ChatScreen.SETTINGS, profileDraft = null, error = null) }
            ChatScreen.NOTIFICATION_SETTINGS ->
                session.state.update { it.copy(screen = ChatScreen.SETTINGS, error = null) }
            else -> Unit
        }
    }
}
