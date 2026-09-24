package com.example.blap

import android.app.Application
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.LocalIdentityStore
import com.example.blap.chat.SqliteChatStore
import com.example.blap.notifications.ConversationVisibilityTracker
import com.example.blap.notifications.MessageNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BlapApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var chatCoordinator: ChatCoordinator
        private set
    lateinit var conversationVisibility: ConversationVisibilityTracker
        private set
    lateinit var messageNotifications: MessageNotificationManager
        private set

    override fun onCreate() {
        super.onCreate()
        chatCoordinator = ChatCoordinator(
            chatStore = SqliteChatStore(this),
            identityStore = LocalIdentityStore(this),
        )
        conversationVisibility = ConversationVisibilityTracker()
        messageNotifications = MessageNotificationManager(
            this,
            chatCoordinator,
            conversationVisibility,
            applicationScope,
        ).also { it.start() }
    }
}
