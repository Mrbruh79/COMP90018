package com.example.blap.event

internal class EventDiscussionNotificationTracker {

    private val seenCommentIds = mutableSetOf<String>()
    private var initialized = false

    fun newComments(
        comments: List<EventDiscussionComment>,
    ): List<EventDiscussionComment> {
        if (!initialized) {
            seenCommentIds += comments.map(EventDiscussionComment::id)
            initialized = true
            return emptyList()
        }

        val newComments = comments.filter { it.id !in seenCommentIds }

        seenCommentIds += comments.map(EventDiscussionComment::id)

        return newComments
    }

    fun reset() {
        seenCommentIds.clear()
        initialized = false
    }
}