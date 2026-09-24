package com.example.blap.notifications

import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
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

    @Test
    fun backgroundConversationDoesNotRequestNotificationCancellation() {
        val uiState = MutableStateFlow(ChatUiState(screen = ChatScreen.CHATS))
        val visibility = ConversationVisibilityTracker()
        val cancellations = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            notificationCancellationConversationIds(uiState, visibility.appForeground)
                .filterNotNull()
                .toList(cancellations)
        }

        uiState.value = ChatUiState(
            screen = ChatScreen.CONVERSATION,
            selectedPeerId = "bob",
        )

        assertTrue(cancellations.isEmpty())
        job.cancel()
    }

    @Test
    fun foregroundReturnRequestsCancellationForAlreadySelectedConversation() {
        val uiState = MutableStateFlow(ChatUiState(
            screen = ChatScreen.CONVERSATION,
            selectedPeerId = "bob",
        ))
        val visibility = ConversationVisibilityTracker()
        val cancellations = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            notificationCancellationConversationIds(uiState, visibility.appForeground)
                .filterNotNull()
                .toList(cancellations)
        }

        assertTrue(cancellations.isEmpty())
        visibility.setAppForeground(true)
        assertTrue(visibility.appForeground.value)
        assertEquals(listOf("bob"), cancellations)

        visibility.setAppForeground(false)
        assertFalse(visibility.appForeground.value)
        visibility.setAppForeground(true)

        assertEquals(listOf("bob", "bob"), cancellations)
        job.cancel()
    }
}
