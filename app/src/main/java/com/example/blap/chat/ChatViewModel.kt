package com.example.blap.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.blap.BlapApplication

class ChatViewModel internal constructor(
    internal val coordinator: ChatCoordinator,
) : ViewModel() {
    val uiState = coordinator.uiState

    fun updateDisplayName(value: String) = coordinator.updateDisplayName(value)
    fun updatePhoneNumber(value: String) = coordinator.updatePhoneNumber(value)
    fun startChat() = coordinator.startChat()
    fun completeSetup() = coordinator.completeSetup()
    fun connectToDevice(id: String) = coordinator.connectToDevice(id)
    fun openConversation(id: String) = coordinator.openConversation(id)
    fun openConversationFromNotification(id: String) =
        coordinator.openConversationFromNotification(id)
    fun showConversationList() = coordinator.showConversationList()
    fun beginManageContacts() = coordinator.beginManageContacts()
    fun beginAddContact() = coordinator.beginAddContact()
    fun openContact(id: String) = coordinator.openContact(id)
    fun messageContact(id: String) = coordinator.messageContact(id)
    fun updateContactDraft(profile: ContactProfile) = coordinator.updateContactDraft(profile)
    fun importScannedContactCard(payload: String) = coordinator.importScannedContactCard(payload)
    fun updateContactName(value: String) = coordinator.updateContactName(value)
    fun updateContactPhone(value: String) = coordinator.updateContactPhone(value)
    fun saveContact() = coordinator.saveContact()
    fun deleteContact() = coordinator.deleteContact()
    fun importDeviceContacts(contacts: List<DeviceContact>) = coordinator.importDeviceContacts(contacts)
    fun beginCreateGroup() = coordinator.beginCreateGroup()
    fun beginGroupSettings() = coordinator.beginGroupSettings()
    fun saveGroupSettings() = coordinator.saveGroupSettings()
    fun deleteCurrentGroup() = coordinator.deleteCurrentGroup()
    fun showMyCard() = coordinator.showMyCard()
    fun editProfile() = coordinator.editProfile()
    fun updateProfile(profile: ContactProfile) = coordinator.updateProfile(profile)
    fun cancelProfileEdit() = coordinator.cancelProfileEdit()
    fun saveProfile() = coordinator.saveProfile()
    fun showSettings() = coordinator.showSettings()
    fun updateConversationSearch(value: String) = coordinator.updateConversationSearch(value)
    fun updateContactSearch(value: String) = coordinator.updateContactSearch(value)
    fun updateGroupName(value: String) = coordinator.updateGroupName(value)
    fun toggleGroupMember(id: String) = coordinator.toggleGroupMember(id)
    fun createPrivateGroup() = coordinator.createPrivateGroup()
    fun handleBack() = coordinator.handleBack()
    fun sendMessage(text: String) = coordinator.sendMessage(text)
    fun disconnect(id: String) = coordinator.disconnect(id)
    fun dismissError() = coordinator.dismissError()
    fun updateMessageDraft(text: String) = coordinator.updateMessageDraft(text)
    fun showError(message: String) = coordinator.showError(message)
    fun showNotice(message: String) = coordinator.showNotice(message)
    fun updateVenueStatus(message: String, checking: Boolean = false) =
        coordinator.updateVenueStatus(message, checking)
    fun stopChat() = coordinator.stopChat()

    companion object {
        const val MAX_NAME_LENGTH = ChatCoordinator.MAX_NAME_LENGTH
        const val MAX_MESSAGE_LENGTH = ChatCoordinator.MAX_MESSAGE_LENGTH
        const val MAX_GROUP_NAME_LENGTH = ChatCoordinator.MAX_GROUP_NAME_LENGTH
        const val MAX_PHONE_LENGTH = ChatCoordinator.MAX_PHONE_LENGTH
        const val MAX_EMAIL_LENGTH = ChatCoordinator.MAX_EMAIL_LENGTH
        const val MAX_BIO_LENGTH = ChatCoordinator.MAX_BIO_LENGTH
        const val MAX_URL_LENGTH = ChatCoordinator.MAX_URL_LENGTH

        fun factory(application: BlapApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(application.chatCoordinator) as T
            }
    }
}
