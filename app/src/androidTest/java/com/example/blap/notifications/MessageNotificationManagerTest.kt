package com.example.blap.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blap.BlapApplication
import com.example.blap.consumeNotificationConversationIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNotificationManagerTest {
    @Test
    fun handledConversationIntentIsConsumed() {
        val intent = Intent().apply {
            action = MessageNotificationManager.ACTION_OPEN_CONVERSATION
            putExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID, "bob")
        }
        var openedConversationId: String? = null

        consumeNotificationConversationIntent(
            intent = intent,
            openConversation = { openedConversationId = it },
            showConversationList = {},
        )

        assertEquals("bob", openedConversationId)
        assertEquals(null, intent.action)
        assertEquals(
            null,
            intent.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID),
        )
    }

    @Test
    fun blankConversationIntentIsConsumedAndFallsBackToConversationList() {
        listOf(null, "   ").forEach { conversationId ->
            val intent = Intent().apply {
                action = MessageNotificationManager.ACTION_OPEN_CONVERSATION
                conversationId?.let {
                    putExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID, it)
                }
            }
            var fallbackCount = 0

            consumeNotificationConversationIntent(
                intent = intent,
                openConversation = { throw AssertionError("Blank route must not open a chat") },
                showConversationList = { fallbackCount++ },
            )

            assertEquals(1, fallbackCount)
            assertEquals(null, intent.action)
            assertEquals(
                null,
                intent.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID),
            )
        }
    }

    @Test
    fun conversationIntentUsesDistinctDataAndExplicitAction() {
        val application = ApplicationProvider.getApplicationContext<BlapApplication>()
        val first = application.messageNotifications.conversationIntent("bob")
        val second = application.messageNotifications.conversationIntent("carol")

        assertNotEquals(first.data, second.data)
        assertEquals(MessageNotificationManager.ACTION_OPEN_CONVERSATION, first.action)
        assertEquals(
            "bob",
            first.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID),
        )
    }

    @Test
    fun applicationCreatesNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(MessageNotificationManager.SERVICE_CHANNEL_ID).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(MessageNotificationManager.MESSAGE_CHANNEL_ID).importance,
        )
    }
}
