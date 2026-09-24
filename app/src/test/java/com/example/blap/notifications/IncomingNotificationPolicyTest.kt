package com.example.blap.notifications

import com.example.blap.chat.ChatScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationPolicyTest {
    @Test
    fun suppressesOnlyTheVisibleConversation() {
        assertFalse(IncomingNotificationPolicy.shouldNotify(
            permissionGranted = true,
            appForeground = true,
            screen = ChatScreen.CONVERSATION,
            selectedConversationId = "bob",
            incomingConversationId = "bob",
        ))
        assertTrue(IncomingNotificationPolicy.shouldNotify(
            permissionGranted = true,
            appForeground = true,
            screen = ChatScreen.CONVERSATION,
            selectedConversationId = "bob",
            incomingConversationId = "carol",
        ))
    }

    @Test
    fun backgroundMessageNotifiesAndPermissionDenialDoesNot() {
        assertTrue(IncomingNotificationPolicy.shouldNotify(
            true, false, ChatScreen.CONVERSATION, "bob", "bob",
        ))
        assertFalse(IncomingNotificationPolicy.shouldNotify(
            false, false, ChatScreen.CHATS, null, "bob",
        ))
    }
}
