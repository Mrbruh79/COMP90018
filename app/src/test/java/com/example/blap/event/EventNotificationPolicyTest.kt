package com.example.blap.event

import com.example.blap.chat.ChatNotificationSettings
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EventNotificationPolicyTest {

    @Test
    fun incomingChatMessageAlerts() {
        val now = 1_000_000L

        val settings = ChatNotificationSettings(
            enabled = true,
        )

        val message = EventChatMessage(
            eventId = "event-1",
            senderId = "other-user",
            senderName = "Other User",
            text = "Hello",
            createdAt = now,
        )

        val result = EventNotificationPolicy.shouldAlertForChat(
            settings = settings,
            message = message,
            accountId = "my-user",
            chatVisible = false,
            now = now,
        )

        assertTrue(result)
    }

    @Test
    fun ownChatMessageDoesNotAlert() {
        val now = 1_000_000L

        val settings = ChatNotificationSettings(
            enabled = true,
        )

        val message = EventChatMessage(
            eventId = "event-1",
            senderId = "my-user",
            senderName = "Me",
            text = "Hello",
            createdAt = now,
        )

        val result = EventNotificationPolicy.shouldAlertForChat(
            settings = settings,
            message = message,
            accountId = "my-user",
            chatVisible = false,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun visibleChatDoesNotAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForChat(
            settings = ChatNotificationSettings(enabled = true),
            message = EventChatMessage(
                eventId = "event-1",
                senderId = "other-user",
                senderName = "Other User",
                text = "Hello",
                createdAt = now,
            ),
            accountId = "my-user",
            chatVisible = true,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun staleChatMessageDoesNotAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForChat(
            settings = ChatNotificationSettings(enabled = true),
            message = EventChatMessage(
                eventId = "event-1",
                senderId = "other-user",
                senderName = "Other User",
                text = "Old message",
                createdAt = now - 120_001L,
            ),
            accountId = "my-user",
            chatVisible = false,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun disabledNotificationsSuppressChatAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForChat(
            settings = ChatNotificationSettings(enabled = false),
            message = EventChatMessage(
                eventId = "event-1",
                senderId = "other-user",
                senderName = "Other User",
                text = "Hello",
                createdAt = now,
            ),
            accountId = "my-user",
            chatVisible = false,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun incomingAnnouncementAlerts() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForAnnouncement(
            settings = ChatNotificationSettings(enabled = true),
            announcement = EventAnnouncement(
                eventId = "event-1",
                adminId = "admin-user",
                adminName = "Admin",
                text = "Gate changed",
                createdAt = now,
            ),
            accountId = "my-user",
            announcementsVisible = false,
            now = now,
        )

        assertTrue(result)
    }

    @Test
    fun ownAnnouncementDoesNotAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForAnnouncement(
            settings = ChatNotificationSettings(enabled = true),
            announcement = EventAnnouncement(
                eventId = "event-1",
                adminId = "my-user",
                adminName = "Me",
                text = "Gate changed",
                createdAt = now,
            ),
            accountId = "my-user",
            announcementsVisible = false,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun visibleAnnouncementsDoNotAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForAnnouncement(
            settings = ChatNotificationSettings(enabled = true),
            announcement = EventAnnouncement(
                eventId = "event-1",
                adminId = "admin-user",
                adminName = "Admin",
                text = "Gate changed",
                createdAt = now,
            ),
            accountId = "my-user",
            announcementsVisible = true,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun staleAnnouncementDoesNotAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForAnnouncement(
            settings = ChatNotificationSettings(enabled = true),
            announcement = EventAnnouncement(
                eventId = "event-1",
                adminId = "admin-user",
                adminName = "Admin",
                text = "Old announcement",
                createdAt = now - 120_001L,
            ),
            accountId = "my-user",
            announcementsVisible = false,
            now = now,
        )

        assertFalse(result)
    }

    @Test
    fun disabledNotificationsSuppressAnnouncementAlert() {
        val now = 1_000_000L

        val result = EventNotificationPolicy.shouldAlertForAnnouncement(
            settings = ChatNotificationSettings(enabled = false),
            announcement = EventAnnouncement(
                eventId = "event-1",
                adminId = "admin-user",
                adminName = "Admin",
                text = "Gate changed",
                createdAt = now,
            ),
            accountId = "my-user",
            announcementsVisible = false,
            now = now,
        )

        assertFalse(result)
    }
}