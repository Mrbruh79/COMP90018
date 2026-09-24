package com.example.blap.notifications

import com.example.blap.chat.ChatScreen

object IncomingNotificationPolicy {
    fun shouldNotify(
        permissionGranted: Boolean,
        appForeground: Boolean,
        screen: ChatScreen,
        selectedConversationId: String?,
        incomingConversationId: String,
    ): Boolean = permissionGranted && !(
        appForeground &&
            screen == ChatScreen.CONVERSATION &&
            selectedConversationId == incomingConversationId
        )

    fun visibleConversationToCancel(
        appForeground: Boolean,
        screen: ChatScreen,
        selectedConversationId: String?,
    ): String? = selectedConversationId.takeIf {
        appForeground && screen == ChatScreen.CONVERSATION
    }
}
