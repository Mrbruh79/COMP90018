package com.example.blap.chat

/** Account-scoped preferences, shared by the settings screen and notification delivery. */
interface NotificationSettingsRepository {
    fun load(): ChatNotificationSettings
    fun save(value: ChatNotificationSettings)
}
