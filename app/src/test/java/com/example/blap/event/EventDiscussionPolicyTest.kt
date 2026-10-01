package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDiscussionPolicyTest {
    private val now = 10_000L
    private val event = CommunityEvent(
        id = "event-1",
        title = "CommonGround Live",
        description = "Discussion policy test",
        latitude = -37.8136,
        longitude = 144.9631,
        startsAt = now - 1_000,
        endsAt = now + 1_000,
        createdBy = "admin-1",
        adminIds = setOf("admin-1"),
        memberIds = setOf("admin-1", "member-1", "member-2"),
    )
    private val member = EventMembership(
        eventId = event.id,
        userId = "member-1",
        displayName = "  Alice Example  ",
        role = EventRole.ATTENDEE,
        joinedAt = now - 2_000,
    )

    @Test
    fun rootCommentIsNormalizedAndCreatedAtDepthZero() {
        val comment = EventDiscussionPolicy.createComment(
            event = event,
            membership = member,
            text = "  Hello everyone  ",
            parentId = null,
            parent = null,
            createdAt = now,
            id = "root-1",
        ).getOrThrow()

        assertEquals("root-1", comment.threadId)
        assertEquals(0, comment.depth)
        assertEquals("Hello everyone", comment.body)
        assertEquals("Alice Example", comment.authorName)
        assertTrue(comment.ancestorIds.isEmpty())
    }

    @Test
    fun nestedReplyInheritsThreadAndAncestorPath() {
        val parent = EventDiscussionComment(
            id = "reply-1",
            eventId = event.id,
            threadId = "root-1",
            parentId = "root-1",
            ancestorIds = listOf("root-1"),
            depth = 1,
            authorId = "member-2",
            authorName = "Bob",
            body = "Parent",
            createdAt = now - 1,
        )

        val reply = EventDiscussionPolicy.createComment(
            event = event,
            membership = member,
            text = "Nested reply",
            parentId = parent.id,
            parent = parent,
            createdAt = now,
            id = "reply-2",
        ).getOrThrow()

        assertEquals("root-1", reply.threadId)
        assertEquals("reply-1", reply.parentId)
        assertEquals(listOf("root-1", "reply-1"), reply.ancestorIds)
        assertEquals(2, reply.depth)
    }

    @Test
    fun missingParentAndCrossEventParentAreRejected() {
        val missing = EventDiscussionPolicy.createComment(
            event = event,
            membership = member,
            text = "Reply",
            parentId = "missing",
            parent = null,
            createdAt = now,
        )
        val otherEventParent = EventDiscussionComment(
            id = "other-root",
            eventId = "event-2",
            authorId = "member-2",
            authorName = "Bob",
            body = "Other event",
        )
        val crossEvent = EventDiscussionPolicy.createComment(
            event = event,
            membership = member,
            text = "Reply",
            parentId = otherEventParent.id,
            parent = otherEventParent,
            createdAt = now,
        )

        assertTrue(missing.exceptionOrNull()?.message.orEmpty().contains("no longer available"))
        assertTrue(crossEvent.exceptionOrNull()?.message.orEmpty().contains("another event"))
    }

    @Test
    fun blankAndOversizedBodiesAreRejected() {
        val blank = EventDiscussionPolicy.createComment(
            event,
            member,
            "   ",
            parentId = null,
            parent = null,
            createdAt = now,
        )
        val oversized = EventDiscussionPolicy.createComment(
            event,
            member,
            "x".repeat(EventDiscussionComment.MAX_ROOT_BODY_LENGTH + 1),
            parentId = null,
            parent = null,
            createdAt = now,
        )

        assertTrue(blank.isFailure)
        assertTrue(oversized.isFailure)
    }

    @Test
    fun writeRulesRejectInactiveMembersAndEndedEvents() {
        assertEquals(
            "Only active event members can participate in the discussion.",
            EventDiscussionPolicy.writeError(event, member.copy(leftAt = now), now),
        )
        assertEquals(
            "This discussion is read-only because the event has ended.",
            EventDiscussionPolicy.writeError(event, member, event.endsAt + 1),
        )
        assertNull(EventDiscussionPolicy.writeError(event, member, now))
    }

    @Test
    fun onlyAuthorOrActiveAdminCanDeleteAnotherUsersComment() {
        val comment = EventDiscussionComment(
            id = "root-1",
            eventId = event.id,
            authorId = "member-2",
            authorName = "Bob",
            body = "Hello",
        )
        val admin = EventMembership(
            eventId = event.id,
            userId = "admin-1",
            displayName = "Admin",
            role = EventRole.PRIMARY_ADMIN,
            joinedAt = now - 2_000,
        )

        assertEquals(
            "You can only delete your own comments.",
            EventDiscussionPolicy.deleteError(event, member, comment, now),
        )
        assertNull(EventDiscussionPolicy.deleteError(event, admin, comment, now))
        assertNull(EventDiscussionPolicy.deleteError(event, member, comment.copy(authorId = member.userId), now))
    }
}
