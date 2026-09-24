package com.example.blap.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.blap.BlapApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNotificationManagerTest {
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
