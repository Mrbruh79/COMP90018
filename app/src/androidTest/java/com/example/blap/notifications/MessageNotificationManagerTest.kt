package com.example.blap.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNotificationManagerTest {
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
