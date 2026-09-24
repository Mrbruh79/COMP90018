package com.example.blap

import android.app.Application
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.LocalIdentityStore
import com.example.blap.chat.NearbyChatManager
import com.example.blap.chat.SqliteChatStore

class BlapApplication : Application() {
    lateinit var chatCoordinator: ChatCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        chatCoordinator = ChatCoordinator(
            nearbyChatController = NearbyChatManager(this),
            chatStore = SqliteChatStore(this),
            identityStore = LocalIdentityStore(this),
        )
    }
}
