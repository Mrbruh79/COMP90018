package com.example.blap.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationPolicyTest {
    private val incoming = ChatMessage(
        id = "1", peerId = "bob", text = "Hello", author = MessageAuthor.PEER,
        sentAt = 1_000_000L, status = MessageStatus.DELIVERED, senderId = "bob",
    )

    @Test fun categorySwitchesAndVisibleChatAreRespected() {
        val settings = ChatNotificationSettings(direct = true, privateGroups = false, openMesh = false)
        assertTrue(ChatNotificationPolicy.shouldAlert(settings, incoming, ConversationType.DIRECT, false, 1_000_100L))
        assertFalse(ChatNotificationPolicy.shouldAlert(settings, incoming, ConversationType.PRIVATE_GROUP, false, 1_000_100L))
        assertFalse(ChatNotificationPolicy.shouldAlert(settings, incoming, ConversationType.OPEN_MESH, false, 1_000_100L))
        assertFalse(ChatNotificationPolicy.shouldAlert(settings, incoming, ConversationType.DIRECT, true, 1_000_100L))
        assertFalse(ChatNotificationPolicy.shouldAlert(settings.copy(enabled = false), incoming,
            ConversationType.DIRECT, false, 1_000_100L))
    }

    @Test fun oldMessagesAndSilentActionsDoNotAlert() {
        assertFalse(ChatNotificationPolicy.shouldAlert(ChatNotificationSettings(), incoming,
            ConversationType.DIRECT, false, 1_200_001L))
        val vote = incoming.copy(text = ChatFeatures.encode(ChatContent.Vote("poll", 1)))
        assertFalse(ChatNotificationPolicy.shouldAlert(ChatNotificationSettings(), vote,
            ConversationType.DIRECT, false, 1_000_100L))
    }
}
