package com.example.blap.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.blap.MainActivity
import com.example.blap.R
import com.example.blap.chat.AcceptedIncomingMessage
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.ChatUiState
import com.example.blap.chat.ConversationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal fun notificationCancellationConversationIds(
    uiStates: Flow<ChatUiState>,
    appForeground: Flow<Boolean>,
): Flow<String?> = combine(uiStates, appForeground) { state, foreground ->
    IncomingNotificationPolicy.visibleConversationToCancel(
        appForeground = foreground,
        screen = state.screen,
        selectedConversationId = state.selectedPeerId,
    )
}.distinctUntilChanged()

class MessageNotificationManager(
    private val context: Context,
    private val coordinator: ChatCoordinator,
    private val visibility: ConversationVisibilityTracker,
    private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val recent = mutableMapOf<String, ArrayDeque<AcceptedIncomingMessage>>()

    fun start() {
        createChannels()
        scope.launch {
            coordinator.acceptedIncomingMessages.collect { event ->
                runCatching { onIncomingMessage(event) }
            }
        }
        scope.launch {
            notificationCancellationConversationIds(
                coordinator.uiState,
                visibility.appForeground,
            )
                .collect { conversationId ->
                    conversationId?.let(::cancelConversation)
                }
        }
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(context.getString(R.string.notification_nearby_active_title))
            .setContentText(context.getString(R.string.notification_nearby_active_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent())
            .build()

    fun cancelConversation(conversationId: String) {
        recent.remove(conversationId)
        manager.cancel(notificationTag(conversationId), MESSAGE_NOTIFICATION_ID)
    }

    internal fun notificationTag(conversationId: String) = "conversation:$conversationId"

    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun onIncomingMessage(event: AcceptedIncomingMessage) {
        val state = coordinator.uiState.value
        if (!IncomingNotificationPolicy.shouldNotify(
                permissionGranted = canPostNotifications(),
                appForeground = visibility.isAppForeground(),
                screen = state.screen,
                selectedConversationId = state.selectedPeerId,
                incomingConversationId = event.message.peerId,
            )
        ) return

        val history = recent.getOrPut(event.message.peerId) { ArrayDeque() }
        history.addLast(event)
        while (history.size > MAX_MESSAGES) history.removeFirst()

        val me = Person.Builder().setName(state.displayName.ifBlank { "You" }).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(event.conversation.name)
            .setGroupConversation(event.conversation.type != ConversationType.DIRECT)
        history.forEach { item ->
            val sender = Person.Builder()
                .setName(item.message.senderName.ifBlank { item.conversation.name })
                .build()
            style.addMessage(item.message.text, item.message.sentAt, sender)
        }

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(event.conversation.name)
            .setContentText(event.message.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(MESSAGE_GROUP_KEY)
            .setContentIntent(conversationPendingIntent(event.message.peerId))
            .build()
        manager.notify(notificationTag(event.message.peerId), MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    internal fun conversationIntent(conversationId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION
            data = Uri.parse("blap://conversation/${Uri.encode(conversationId)}")
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun conversationPendingIntent(conversationId: String): PendingIntent =
        PendingIntent.getActivity(
            context, 0, conversationIntent(conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.createNotificationChannels(listOf(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.notification_channel_nearby_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.notification_channel_messages_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        ))
    }

    companion object {
        const val SERVICE_CHANNEL_ID = "nearby_service"
        const val MESSAGE_CHANNEL_ID = "incoming_messages"
        const val SERVICE_NOTIFICATION_ID = 1001
        const val MESSAGE_NOTIFICATION_ID = 1002
        const val ACTION_OPEN_CONVERSATION = "com.example.blap.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val MESSAGE_GROUP_KEY = "blap_messages"
        private const val MAX_MESSAGES = 5
    }
}
