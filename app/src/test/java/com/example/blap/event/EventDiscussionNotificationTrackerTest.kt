package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiscussionNotificationTrackerTest {

    @Test
    fun initialSnapshotDoesNotReturnNotifications() {
        val tracker = EventDiscussionNotificationTracker()

        val comments = listOf(
            comment("comment-1"),
            comment("comment-2"),
        )

        assertTrue(tracker.newComments(comments).isEmpty())
    }

    @Test
    fun laterSnapshotReturnsOnlyNewComments() {
        val tracker = EventDiscussionNotificationTracker()

        tracker.newComments(
            listOf(
                comment("comment-1"),
                comment("comment-2"),
            ),
        )

        val result = tracker.newComments(
            listOf(
                comment("comment-1"),
                comment("comment-2"),
                comment("comment-3"),
            ),
        )

        assertEquals(listOf("comment-3"), result.map { it.id })
    }

    @Test
    fun repeatedSnapshotDoesNotReturnDuplicateNotifications() {
        val tracker = EventDiscussionNotificationTracker()

        tracker.newComments(listOf(comment("comment-1")))

        tracker.newComments(
            listOf(
                comment("comment-1"),
                comment("comment-2"),
            ),
        )

        val result = tracker.newComments(
            listOf(
                comment("comment-1"),
                comment("comment-2"),
            ),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun resetTreatsNextSnapshotAsNewBaseline() {
        val tracker = EventDiscussionNotificationTracker()

        tracker.newComments(listOf(comment("comment-1")))
        tracker.reset()

        val result = tracker.newComments(
            listOf(
                comment("comment-1"),
                comment("comment-2"),
            ),
        )

        assertTrue(result.isEmpty())
    }

    private fun comment(id: String) = EventDiscussionComment(
        id = id,
        eventId = "event-1",
        threadId = "thread-1",
        parentId = null,
        ancestorIds = emptyList(),
        depth = 0,
        authorId = "other-user",
        authorName = "Other User",
        body = "Test comment",
        createdAt = 1_000_000L,
    )
}