package com.example.blap.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.blap.MainActivity
import com.example.blap.R

data class ChatNotificationSettings(
    val enabled: Boolean = true,
    val direct: Boolean = true,
    val privateGroups: Boolean = true,
    val openMesh: Boolean = false,
    val showPreview: Boolean = true,
)

class ChatNotificationSettingsStore(context: Context, scope: String) {
    private val preferences = context.getSharedPreferences("chat_notifications$scope", Context.MODE_PRIVATE)

    fun load(): ChatNotificationSettings = ChatNotificationSettings(
        enabled = preferences.getBoolean("enabled", true),
        direct = preferences.getBoolean("direct", true),
        privateGroups = preferences.getBoolean("private_groups", true),
        openMesh = preferences.getBoolean("open_mesh", false),
        showPreview = preferences.getBoolean("show_preview", true),
    )

    fun save(value: ChatNotificationSettings) {
        preferences.edit {
            putBoolean("enabled", value.enabled)
            putBoolean("direct", value.direct)
            putBoolean("private_groups", value.privateGroups)
            putBoolean("open_mesh", value.openMesh)
            putBoolean("show_preview", value.showPreview)
        }
    }
}

interface ChatNotifier {
    fun incoming(message: ChatMessage, conversationName: String, type: ConversationType, chatVisible: Boolean)
}

object NoopChatNotifier : ChatNotifier {
    override fun incoming(message: ChatMessage, conversationName: String, type: ConversationType, chatVisible: Boolean) = Unit
}

object ChatNotificationPolicy {
    fun shouldAlert(
        settings: ChatNotificationSettings,
        message: ChatMessage,
        type: ConversationType,
        chatVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!settings.enabled || message.author != MessageAuthor.PEER || chatVisible ||
            message.sentAt < now - 120_000L) return false
        val content = ChatFeatures.decode(message.text)
        if (content is ChatContent.Vote || content is ChatContent.Edit || content is ChatContent.Delete) return false
        return when (type) {
            ConversationType.DIRECT -> settings.direct
            ConversationType.PRIVATE_GROUP -> settings.privateGroups
            ConversationType.OPEN_MESH -> settings.openMesh
        }
    }
}

class AndroidChatNotifier(
    private val context: Context,
    private val settingsStore: ChatNotificationSettingsStore,
) : ChatNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun incoming(message: ChatMessage, conversationName: String, type: ConversationType, chatVisible: Boolean) {
        val settings = settingsStore.load()
        if (!ChatNotificationPolicy.shouldAlert(settings, message, type, chatVisible)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (settings.showPreview) conversationName else "New message")
            .setContentText(if (settings.showPreview) ChatFeatures.preview(message.text) else "Open CommonGround to read it")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        manager.notify(message.id.hashCode(), notification)
    }

    private companion object { const val CHANNEL_ID = "chat_messages" }
}
