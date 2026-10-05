package com.example.blap.event

/**
 * Account-scoped, in-process observers. Screen observers remain owned by the UI coordinators.
 * No Android notification APIs live here; all alerts go through [EventNotifier].
 */
internal class EventNotificationCoordinator(
    private val remote: EventRemoteRepository,
    private val delegate: EventNotifier,
    private val currentState: () -> EventUiState,
    private val eventsVisible: () -> Boolean,
    private val saveCloudAnnouncement: (EventAnnouncement) -> Unit,
    private val clock: () -> Long,
) : EventNotifier, AutoCloseable {
    private class ThreadSubscription {
        val tracker = EventDiscussionNotificationTracker()
        var observer: AutoCloseable? = null
    }

    private class Subscription(val startedAt: Long) {
        var announcements: AutoCloseable? = null
        var roots: AutoCloseable? = null
        val threads = mutableMapOf<String, ThreadSubscription>()
        val rootTracker = EventDiscussionNotificationTracker()
        var announcementsInitialized = false
        val announcementIds = mutableSetOf<String>()
        fun close() {
            announcements?.close()
            roots?.close()
            threads.values.forEach { it.observer?.close() }
        }
    }

    private val subscriptions = mutableMapOf<String, Subscription>()
    private val handledAnnouncements = mutableSetOf<Pair<String, String>>()
    private var accountId = ""
    @Volatile private var closed = false

    @Synchronized
    fun updateSubscriptions(state: EventUiState) {
        if (closed) return
        if (accountId != state.currentUserId) {
            reset()
            accountId = state.currentUserId
        }
        val joined = state.events.filter {
            accountId.isNotBlank() && accountId in it.memberIds && !it.isDeleted && it.endsAt >= clock()
        }.associateBy(CommunityEvent::id)
        subscriptions.keys.filterNot(joined::containsKey).forEach { id ->
            subscriptions.remove(id)?.close()
        }
        joined.keys.filterNot(subscriptions::containsKey).forEach { eventId ->
            val subscription = Subscription(clock())
            subscriptions[eventId] = subscription
            subscription.announcements = remote.observeAnnouncements(
                eventId = eventId,
                onAnnouncements = { announcements -> synchronized(this) {
                    val event = eligibleEvent(eventId, subscription) ?: return@synchronized
                    val initialized = subscription.announcementsInitialized
                    subscription.announcementsInitialized = true
                    announcements.forEach { announcement ->
                        val fresh = subscription.announcementIds.add(announcement.id)
                        // Mark history before relaying to mesh, which may immediately echo it back.
                        val unseen = handledAnnouncements.add(eventId to announcement.id)
                        if (initialized && fresh && unseen && announcement.createdAt > subscription.startedAt) {
                            delegate.incomingAnnouncement(event, announcement, announcementVisible(eventId))
                        }
                        saveCloudAnnouncement(announcement)
                    }
                } },
                // A listener failure must not turn into an empty baseline or a screen error.
                onError = {},
            )
            subscription.roots = remote.observeDiscussionNotificationRoots(
                eventId = eventId,
                onComments = { roots -> synchronized(this) {
                    val event = eligibleEvent(eventId, subscription) ?: return@synchronized
                    val newRoots = subscription.rootTracker.newComments(roots)
                    newRoots.filter { it.createdAt > subscription.startedAt }.forEach { comment ->
                        incomingDiscussionComment(event, comment, false)
                    }
                    val rootIds = roots.mapTo(mutableSetOf(), EventDiscussionComment::id)
                    subscription.threads.keys.filterNot(rootIds::contains).forEach { id ->
                        subscription.threads.remove(id)?.observer?.close()
                    }
                    roots.filterNot { subscription.threads.containsKey(it.id) }.forEach { root ->
                        val thread = ThreadSubscription()
                        val tracker = thread.tracker
                        // Reply history is filtered by startedAt, including the first thread snapshot.
                        tracker.newComments(listOf(root))
                        subscription.threads[root.id] = thread
                        thread.observer = remote.observeDiscussionThread(
                            eventId, root.id,
                            onComments = { comments -> synchronized(this) threadUpdate@{
                                val currentEvent = eligibleEvent(eventId, subscription) ?: return@threadUpdate
                                if (subscription.threads[root.id] !== thread) return@threadUpdate
                                tracker.newComments(comments).filter {
                                    !it.isRoot && it.createdAt > subscription.startedAt
                                }.forEach { incomingDiscussionComment(currentEvent, it, false) }
                            } },
                            onError = {},
                        )
                    }
                } },
                onError = {},
            )
        }
    }

    private fun eligibleEvent(eventId: String, subscription: Subscription): CommunityEvent? {
        if (closed || subscriptions[eventId] !== subscription) return null
        val state = currentState()
        if (state.currentUserId != accountId) return null
        return state.events.firstOrNull {
            it.id == eventId && accountId in it.memberIds && !it.isDeleted && it.endsAt >= clock()
        }
    }

    private fun announcementVisible(eventId: String): Boolean = currentState().let {
        eventsVisible() && it.selectedEventId == eventId && it.page == EventPage.ANNOUNCEMENTS
    }

    @Synchronized
    override fun incomingAnnouncement(event: CommunityEvent, announcement: EventAnnouncement, announcementsVisible: Boolean) {
        if (!closed && handledAnnouncements.add(event.id to announcement.id)) {
            delegate.incomingAnnouncement(event, announcement, announcementVisible(event.id))
        }
    }

    override fun incomingChatMessage(event: CommunityEvent, message: EventChatMessage, chatVisible: Boolean) {
        if (!closed) delegate.incomingChatMessage(event, message, eventsVisible() && chatVisible)
    }

    override fun incomingDiscussionComment(event: CommunityEvent, comment: EventDiscussionComment, discussionVisible: Boolean) {
        if (!closed) delegate.incomingDiscussionComment(event, comment,
            EventNotificationVisibility.discussion(currentState(), eventsVisible(), comment))
    }

    @Synchronized
    fun reset() {
        val previous = subscriptions.values.toList()
        subscriptions.clear()
        previous.forEach(Subscription::close)
        handledAnnouncements.clear()
    }

    @Synchronized
    override fun close() {
        closed = true
        reset()
    }
}
