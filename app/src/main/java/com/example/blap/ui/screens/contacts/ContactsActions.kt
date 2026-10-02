package com.example.blap.ui.screens.contacts

import com.example.blap.chat.ContactProfile

data class ContactsActions(
    val onManageContacts: () -> Unit,
    val onContactSearchChanged: (String) -> Unit,
    val onBeginAddContact: () -> Unit,
    val onOpenContact: (String) -> Unit,
    val onScanContact: () -> Unit,
    val onImportContacts: () -> Unit,
    val onMessageContact: (String) -> Unit,
    val onCheckContactOnline: (String) -> Unit,
    val onContactDraftChanged: (ContactProfile) -> Unit,
    val onSaveContact: () -> Unit,
    val onDeleteContact: () -> Unit,
    val onCloseContactEditor: () -> Unit,
    val onOpenChatContactProfile: () -> Unit,
    val onSaveCurrentChatContact: () -> Unit,
    val onCloseChatContactProfile: () -> Unit,
    val onSelectOnlineAccount: (String) -> Unit,
    val onCancelAccountSelection: () -> Unit,
)
