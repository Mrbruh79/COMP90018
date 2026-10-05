package com.example.blap.event

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.blap.MainActivity
import com.example.blap.R
import com.example.blap.chat.NotificationSettingsRepository

class AndroidEventNotifier(
    private val context: Context,
    private val settingsStore: NotificationSettingsRepository,
    private val accountId: String,
) : EventNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun incomingAnnouncement(
        event: CommunityEvent,
        announcement: EventAnnouncement,
        announcementsVisible: Boolean,
    ) {
        val settings = settingsStore.load()

        if (!EventNotificationPolicy.shouldAlertForAnnouncement(
                settings = settings,
                announcement = announcement,
                accountId = accountId,
                announcementsVisible = announcementsVisible,
            )
        ) return

        if (!hasNotificationPermission()) return

        createChannels()

        val notification = NotificationCompat.Builder(
            context,
            ANNOUNCEMENT_CHANNEL_ID,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (settings.showPreview) {
                    "${event.title} announcement"
                } else {
                    "New event announcement"
                },
            )
            .setContentText(
                if (settings.showPreview) {
                    "${announcement.adminName}: ${announcement.text}"
                } else {
                    "Open CommonGround to read it"
                },
            )
            .setContentIntent(createContentIntent(event.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()

        manager.notify(
            "event-announcement:${announcement.id}".hashCode(),
            notification,
        )
    }

    override fun incomingChatMessage(
        event: CommunityEvent,
        message: EventChatMessage,
        chatVisible: Boolean,
    ) {
        val settings = settingsStore.load()

        if (!EventNotificationPolicy.shouldAlertForChat(
                settings = settings,
                message = message,
                accountId = accountId,
                chatVisible = chatVisible,
            )
        ) return

        if (!hasNotificationPermission()) return

        createChannels()

        val notification = NotificationCompat.Builder(
            context,
            EVENT_CHAT_CHANNEL_ID,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (settings.showPreview) {
                    event.title
                } else {
                    "New event message"
                },
            )
            .setContentText(
                if (settings.showPreview) {
                    "${message.senderName}: ${message.text}"
                } else {
                    "Open CommonGround to read it"
                },
            )
            .setContentIntent(createContentIntent(event.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        manager.notify(
            "event-chat:${message.id}".hashCode(),
            notification,
        )
    }

    override fun incomingDiscussionComment(
        event: CommunityEvent,
        comment: EventDiscussionComment,
        discussionVisible: Boolean,
    ) {
        val settings = settingsStore.load()

        if (!EventNotificationPolicy.shouldAlertForDiscussion(
                settings = settings,
                comment = comment,
                accountId = accountId,
                discussionVisible = discussionVisible,
            )
        ) return

        if (!hasNotificationPermission()) return

        createChannels()

        val notification = NotificationCompat.Builder(
            context,
            EVENT_DISCUSSION_CHANNEL_ID,
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                if (settings.showPreview) {
                    if (comment.isRoot) {
                        "${event.title} discussion"
                    } else {
                        "New reply in ${event.title}"
                    }
                } else {
                    if (comment.isRoot) {
                        "New discussion comment"
                    } else {
                        "New discussion reply"
                    }
                },
            )
            .setContentText(
                if (settings.showPreview) {
                    "${comment.authorName}: ${comment.body}"
                } else {
                    "Open CommonGround to read it"
                },
            )
            .setContentIntent(createContentIntent(event.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        manager.notify(
            "event-discussion:${comment.id}".hashCode(),
            notification,
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return

        manager.createNotificationChannel(
            NotificationChannel(
                ANNOUNCEMENT_CHANNEL_ID,
                "Event announcements",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        manager.createNotificationChannel(
            NotificationChannel(
                EVENT_CHAT_CHANNEL_ID,
                "Event messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        manager.createNotificationChannel(
            NotificationChannel(
                EVENT_DISCUSSION_CHANNEL_ID,
                "Event discussions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun createContentIntent(eventId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Extras alone do not distinguish PendingIntents (nor do hash codes guarantee uniqueness).
            data = Uri.Builder().scheme("commonground").authority("event-notification")
                .appendPath(accountId).appendPath(eventId).build()
            putExtra(EventNotificationTarget.EXTRA_EVENT_ID, eventId)
            putExtra(EventNotificationTarget.EXTRA_ACCOUNT_ID, accountId)
        }

        return PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ANNOUNCEMENT_CHANNEL_ID = "event_announcements"
        const val EVENT_CHAT_CHANNEL_ID = "event_messages"
        const val EVENT_DISCUSSION_CHANNEL_ID = "event_discussions"
    }
}
