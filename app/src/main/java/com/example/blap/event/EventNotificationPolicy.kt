package com.example.blap.event

import com.example.blap.chat.ChatNotificationSettings

object EventNotificationPolicy {

    fun shouldAlertForChat(
        settings: ChatNotificationSettings,
        message: EventChatMessage,
        accountId: String,
        chatVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!settings.enabled) return false
        if (chatVisible) return false
        if (message.senderId == accountId) return false
        if (message.createdAt < now - RECENT_WINDOW_MS) return false

        return true
    }

    fun shouldAlertForAnnouncement(
        settings: ChatNotificationSettings,
        announcement: EventAnnouncement,
        accountId: String,
        announcementsVisible: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!settings.enabled) return false
        if (announcementsVisible) return false
        if (announcement.adminId == accountId) return false
        if (announcement.createdAt < now - RECENT_WINDOW_MS) return false

        return true
    }

    private const val RECENT_WINDOW_MS = 120_000L
}