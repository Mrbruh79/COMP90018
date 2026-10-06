package com.example.blap.event

import com.example.blap.chat.ChatNotificationSettings
import org.junit.Assert.*
import org.junit.Test

class EventNotificationCoordinatorTest {
    @Test fun initialSnapshotsAndCacheThenServerHistoryAreSilent() = withFixture { f ->
        val root = f.comment("old", createdAt = f.now - 1)
        f.remote.roots.getValue("event")(emptyList())
        f.remote.roots.getValue("event")(listOf(root))
        f.remote.threads.getValue("event" to "old")(listOf(root, f.reply("old-reply", root, f.now - 1)))
        f.remote.announcements.getValue("event")(emptyList())
        f.remote.announcements.getValue("event")(listOf(f.announcement("old", f.now - 1)))
        assertTrue(f.alerts.isEmpty())
        assertEquals(1, f.saved.size)
    }

    @Test fun newRootAndRacingReplyAlertWithoutOpeningDiscussion() = withFixture { f ->
        f.remote.roots.getValue("event")(emptyList())
        f.now++
        val root = f.comment("root")
        f.remote.roots.getValue("event")(listOf(root))
        val reply = f.reply("reply", root)
        f.remote.threads.getValue("event" to "root")(listOf(root, reply))
        f.remote.roots.getValue("event")(listOf(root.copy(likeCount = 1)))
        f.remote.threads.getValue("event" to "root")(listOf(root, reply))
        assertEquals(listOf("discussion:root", "discussion:reply"), f.alerts)
    }

    @Test fun oldThreadReplyIsObservedWithoutOpeningThread() = withFixture { f ->
        val root = f.comment("old", createdAt = f.now - 500_000)
        f.remote.roots.getValue("event")(listOf(root))
        val thread = f.remote.threads.getValue("event" to "old")
        thread(listOf(root))
        f.now++
        thread(listOf(root, f.reply("new-reply", root)))
        assertEquals(listOf("discussion:new-reply"), f.alerts)
    }

    @Test fun existingThreadFirstSnapshotNotifiesOnlyPostSubscriptionRepliesOnce() = withFixture { f ->
        val root = f.comment("old", createdAt = f.now - 500_000)
        val historicalReply = f.reply("history", root, f.now - 1)
        val boundaryReply = f.reply("at-subscription", root)
        f.remote.roots.getValue("event")(listOf(root))
        val thread = f.remote.threads.getValue("event" to root.id)
        f.now++
        val racingReply = f.reply("racing-reply", root)

        val firstSnapshot = listOf(root, historicalReply, boundaryReply, racingReply)
        thread(firstSnapshot)
        assertEquals(listOf("discussion:racing-reply"), f.alerts)
        thread(firstSnapshot)
        thread(firstSnapshot.map { it.copy(likeCount = 1) })
        assertEquals(listOf("discussion:racing-reply"), f.alerts)
    }

    @Test fun firstSnapshotRepliesKeepPolicySuppressionAndAreNotReplayed() {
        listOf("own", "deleted", "admin-deleted", "stale", "visible", "disabled").forEach { reason ->
            withFixture { f ->
                val root = f.comment("old", createdAt = f.now - 500_000)
                f.remote.roots.getValue("event")(listOf(root))
                val thread = f.remote.threads.getValue("event" to root.id)
                f.now++
                val reply = f.reply("reply", root).let {
                    when (reason) {
                        "own" -> it.copy(authorId = "me")
                        "deleted" -> it.copy(deletedAt = f.now)
                        "admin-deleted" -> it.copy(deletedByAdmin = true)
                        else -> it
                    }
                }
                if (reason == "stale") f.now += 120_001
                if (reason == "disabled") f.settings = ChatNotificationSettings(enabled = false)
                if (reason == "visible") {
                    f.visible = true
                    f.state = f.state.copy(selectedEventId = "event", page = EventPage.DISCUSSION_THREAD,
                        selectedDiscussionThreadId = root.id)
                }
                thread(listOf(root, reply))
                assertTrue(reason, f.alerts.isEmpty())
                f.visible = false
                f.settings = ChatNotificationSettings()
                thread(listOf(root, reply))
                assertTrue(reason, f.alerts.isEmpty())
            }
        }
    }

    @Test fun visibleContentIsConsumedButBackgroundAndOtherThreadsAlert() = withFixture { f ->
        val root = f.comment("root")
        f.remote.roots.getValue("event")(listOf(root))
        val thread = f.remote.threads.getValue("event" to "root")
        thread(listOf(root))
        f.state = f.state.copy(selectedEventId = "event", page = EventPage.DISCUSSION_THREAD,
            selectedDiscussionThreadId = "root")
        f.visible = true
        f.now++
        val visibleReply = f.reply("visible", root)
        thread(listOf(root, visibleReply))
        assertTrue(f.alerts.isEmpty())
        f.visible = false
        thread(listOf(root, visibleReply))
        thread(listOf(root, visibleReply, f.reply("background", root)))
        f.visible = true
        f.state = f.state.copy(selectedDiscussionThreadId = "other")
        thread(listOf(root, f.reply("other-thread", root)))
        assertEquals(listOf("discussion:background", "discussion:other-thread"), f.alerts)
    }

    @Test fun rootListDoesNotSuppressUnopenedReplies() = withFixture { f ->
        val root = f.comment("root")
        f.state = f.state.copy(selectedEventId = "event", page = EventPage.DISCUSSION)
        f.visible = true
        f.remote.roots.getValue("event")(listOf(root))
        val thread = f.remote.threads.getValue("event" to "root")
        thread(listOf(root))
        f.now++
        thread(listOf(root, f.reply("reply", root)))
        f.remote.roots.getValue("event")(listOf(root, f.comment("visible-root")))
        assertEquals(listOf("discussion:reply"), f.alerts)
    }

    @Test fun ownDeletedStaleAndDisabledContentDoesNotAlert() = withFixture { f ->
        f.remote.roots.getValue("event")(emptyList())
        f.now++
        val comments = listOf(f.comment("own").copy(authorId = "me"),
            f.comment("author-deleted").copy(deletedAt = f.now),
            f.comment("admin-deleted").copy(deletedByAdmin = true),
            f.comment("stale", createdAt = f.now - 120_001))
        f.remote.roots.getValue("event")(comments)
        f.settings = ChatNotificationSettings(enabled = false)
        f.remote.roots.getValue("event")(comments + f.comment("disabled"))
        f.settings = ChatNotificationSettings()
        f.remote.roots.getValue("event")(comments + f.comment("disabled"))
        assertTrue(f.alerts.isEmpty())
    }

    @Test fun cloudAndMeshNotifyOnceInEitherOrderIncludingCloudAlreadyStoredByUi() = withFixture { f ->
        val cloud = f.remote.announcements.getValue("event")
        cloud(emptyList())
        f.now++
        val first = f.announcement("cloud-first")
        f.saved += first // An independent UI fetch can store it before the notification listener.
        cloud(listOf(first))
        f.coordinator.incomingAnnouncement(f.event, first, false)
        val second = f.announcement("mesh-first")
        f.coordinator.incomingAnnouncement(f.event, second, false)
        cloud(listOf(first, second))
        cloud(listOf(first, second.copy(revision = second.revision + 1)))
        assertEquals(listOf("announcement:cloud-first", "announcement:mesh-first"), f.alerts)
    }

    @Test fun initialCloudHistoryDoesNotAlertWhenEchoedByMesh() = withFixture { f ->
        val history = f.announcement("history")
        f.remote.announcements.getValue("event")(listOf(history))
        f.coordinator.incomingAnnouncement(f.event, history, false)
        assertTrue(f.alerts.isEmpty())
    }

    @Test fun announcementAndChatVisibilityIncludeForegroundAndEventsDestination() = withFixture { f ->
        f.remote.announcements.getValue("event")(emptyList())
        f.state = f.state.copy(selectedEventId = "event", page = EventPage.ANNOUNCEMENTS)
        f.visible = true
        f.now++
        f.remote.announcements.getValue("event")(listOf(f.announcement("visible")))
        f.visible = false
        f.remote.announcements.getValue("event")(listOf(f.announcement("background")))
        val message = EventChatMessage(eventId = "event", senderId = "other", senderName = "Other", text = "Hi", createdAt = f.now)
        f.coordinator.incomingChatMessage(f.event, message, true)
        f.visible = true
        f.coordinator.incomingChatMessage(f.event, message.copy(id = "visible"), true)
        assertEquals(listOf("announcement:background", "chat:${message.id}"), f.alerts)
    }

    @Test fun membershipRemovalClosesListenersAndIgnoresLateCallbacks() = withFixture { f ->
        val roots = f.remote.roots.getValue("event")
        val announcements = f.remote.announcements.getValue("event")
        val root = f.comment("root")
        roots(listOf(root))
        val thread = f.remote.threads.getValue("event" to "root")
        thread(listOf(root))
        f.state = f.state.copy(events = listOf(f.event.copy(memberIds = setOf("other"))))
        f.coordinator.updateSubscriptions(f.state)
        assertTrue(f.remote.roots.isEmpty())
        assertTrue(f.remote.threads.isEmpty())
        assertTrue(f.remote.announcements.isEmpty())
        f.now++
        roots(listOf(f.comment("late")))
        thread(listOf(root, f.reply("late", root)))
        announcements(listOf(f.announcement("late")))
        assertTrue(f.alerts.isEmpty())
        assertTrue(f.saved.isEmpty())
    }

    @Test fun accountSwitchAndCloseInvalidateOldObservers() = withFixture { f ->
        val old = f.remote.roots.getValue("event")
        old(emptyList())
        f.state = f.state.copy(currentUserId = "other")
        f.coordinator.updateSubscriptions(f.state)
        f.now++
        old(listOf(f.comment("old-account")))
        val current = f.remote.roots.getValue("event")
        current(emptyList())
        f.coordinator.close()
        current(listOf(f.comment("closed")))
        assertTrue(f.alerts.isEmpty())
        assertTrue(f.remote.roots.isEmpty())
    }

    @Test fun deletionAndReappearanceOfThreadInvalidatesOldCallback() = withFixture { f ->
        val roots = f.remote.roots.getValue("event")
        val root = f.comment("root")
        roots(listOf(root))
        val oldThread = f.remote.threads.getValue("event" to "root")
        oldThread(listOf(root))
        roots(emptyList())
        roots(listOf(root))
        f.now++
        oldThread(listOf(root, f.reply("late", root)))
        assertTrue(f.alerts.isEmpty())
    }

    @Test fun observesAllJoinedEventsButNotDiscoveredDeletedOrEndedEvents() = withFixture { f ->
        f.state = f.state.copy(events = listOf(f.event, f.event.copy(id = "second"),
            f.event.copy(id = "discovered", memberIds = setOf("other")),
            f.event.copy(id = "deleted", deletedAt = f.now), f.event.copy(id = "ended", endsAt = f.now - 1)))
        f.coordinator.updateSubscriptions(f.state)
        assertEquals(setOf("event", "second"), f.remote.roots.keys)
        assertEquals(setOf("event", "second"), f.remote.announcements.keys)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally { f.coordinator.close() }
    }

    private class Fixture : EventNotifier {
        var now = 1_000_000L
        var visible = false
        var settings = ChatNotificationSettings()
        val event = CommunityEvent("event", "Meetup", "", latitude = 0.0, longitude = 0.0,
            startsAt = 0, endsAt = 9_000_000, createdBy = "other", memberIds = setOf("me", "other"))
        var state = EventUiState(events = listOf(event), currentUserId = "me")
        val remote = NotificationRemote()
        val alerts = mutableListOf<String>()
        val saved = mutableListOf<EventAnnouncement>()
        val coordinator = EventNotificationCoordinator(remote, this, { state }, { visible }, { saved += it }, { now })
            .also { it.updateSubscriptions(state) }
        fun comment(id: String, createdAt: Long = now) = EventDiscussionComment(id = id, eventId = "event",
            authorId = "other", authorName = "Other", body = "Hello", createdAt = createdAt)
        fun reply(id: String, root: EventDiscussionComment, createdAt: Long = now) = comment(id, createdAt).copy(
            threadId = root.id, parentId = root.id, ancestorIds = listOf(root.id), depth = 1)
        fun announcement(id: String, createdAt: Long = now) = EventAnnouncement(id = id, eventId = "event",
            adminId = "other", adminName = "Other", text = "Hello", createdAt = createdAt)
        override fun incomingDiscussionComment(event: CommunityEvent, comment: EventDiscussionComment, discussionVisible: Boolean) {
            if (EventNotificationPolicy.shouldAlertForDiscussion(settings, comment, state.currentUserId, discussionVisible, now))
                alerts += "discussion:${comment.id}"
        }
        override fun incomingAnnouncement(event: CommunityEvent, announcement: EventAnnouncement, announcementsVisible: Boolean) {
            if (EventNotificationPolicy.shouldAlertForAnnouncement(settings, announcement, state.currentUserId, announcementsVisible, now))
                alerts += "announcement:${announcement.id}"
        }
        override fun incomingChatMessage(event: CommunityEvent, message: EventChatMessage, chatVisible: Boolean) {
            if (EventNotificationPolicy.shouldAlertForChat(settings, message, state.currentUserId, chatVisible, now))
                alerts += "chat:${message.id}"
        }
    }
}

internal open class NotificationRemote : EventRemoteRepository {
    val roots = mutableMapOf<String, (List<EventDiscussionComment>) -> Unit>()
    val threads = mutableMapOf<Pair<String, String>, (List<EventDiscussionComment>) -> Unit>()
    val announcements = mutableMapOf<String, (List<EventAnnouncement>) -> Unit>()
    override fun observeDiscussionRoots(eventId: String, limit: Int, onComments: (List<EventDiscussionComment>) -> Unit,
        onError: (Throwable) -> Unit): AutoCloseable = AutoCloseable {}
    override fun observeDiscussionNotificationRoots(eventId: String, onComments: (List<EventDiscussionComment>) -> Unit,
        onError: (Throwable) -> Unit): AutoCloseable {
        roots[eventId] = onComments
        return AutoCloseable { roots.remove(eventId) }
    }
    override fun observeDiscussionThread(eventId: String, threadId: String, onComments: (List<EventDiscussionComment>) -> Unit,
        onError: (Throwable) -> Unit): AutoCloseable {
        threads[eventId to threadId] = onComments
        return AutoCloseable { threads.remove(eventId to threadId) }
    }
    override fun observeAnnouncements(eventId: String, limit: Int, onAnnouncements: (List<EventAnnouncement>) -> Unit,
        onError: (Throwable) -> Unit): AutoCloseable {
        announcements[eventId] = onAnnouncements
        return AutoCloseable { announcements.remove(eventId) }
    }
    override suspend fun requireUserId() = "me"
    override fun hasSignedInAccount() = true
    override suspend fun createEvent(event: CommunityEvent, creator: EventMembership) = unused()
    override suspend fun listEvents(): List<CommunityEvent> = unused()
    override suspend fun updateEvent(event: CommunityEvent) = unused()
    override suspend fun deleteEvent(eventId: String) = unused()
    override suspend fun listInvitations(): List<EventInvitation> = unused()
    override suspend fun listEventInvitations(eventId: String): List<EventInvitation> = unused()
    override suspend fun findInvitee(identifier: String): EventInvitee? = unused()
    override suspend fun invite(invitation: EventInvitation) = unused()
    override suspend fun acceptInvitation(invitation: EventInvitation, membership: EventMembership): CommunityEvent? = unused()
    override suspend fun declineInvitation(invitation: EventInvitation) = unused()
    override suspend fun revokeInvitation(invitation: EventInvitation) = unused()
    override suspend fun joinEvent(membership: EventMembership) = unused()
    override suspend fun leaveEvent(event: CommunityEvent, membership: EventMembership, leftAt: Long) = unused()
    override suspend fun listMembers(eventId: String): List<EventMembership> = unused()
    override suspend fun promoteToCoAdmin(eventId: String, userId: String) = unused()
    override suspend fun blockMember(event: CommunityEvent, userId: String, blockedAt: Long) = unused()
    override suspend fun registerAdminPublicKey(eventId: String, userId: String, encodedPublicKey: String) = unused()
    override suspend fun saveAnnouncement(announcement: EventAnnouncement) = unused()
    override suspend fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> = unused()
    override fun observeDiscussionLikes(eventId: String, onLikedCommentIds: (Set<String>) -> Unit,
        onError: (Throwable) -> Unit): AutoCloseable? = unused()
    override suspend fun createDiscussionComment(comment: EventDiscussionComment) = unused()
    override suspend fun setDiscussionLike(comment: EventDiscussionComment, liked: Boolean) = unused()
    override suspend fun deleteOwnDiscussionComment(comment: EventDiscussionComment, deletedAt: Long) = unused()
    override suspend fun deleteDiscussionBranchAsAdmin(comment: EventDiscussionComment, deletedAt: Long) = unused()
    private fun unused(): Nothing = error("Unexpected repository call")
}
