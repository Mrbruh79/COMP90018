package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFeaturesTest {
    @Test fun structuredMessagesRoundTrip() {
        val contents = listOf(
            ChatContent.Reply("Yes, I'll go", "message-1", "Are you coming?"),
            ChatContent.Poll("Where should we meet?", listOf("Library", "Café ☕", "Park")),
            ChatContent.Vote("poll-1", 2),
            ChatContent.Edit("message-1", "Updated text"),
            ChatContent.Delete("message-1"),
        )
        contents.forEach { assertEquals(it, ChatFeatures.decode(ChatFeatures.encode(it))) }
        assertEquals(ChatContent.Text("Hello"), ChatFeatures.decode("Hello"))
        assertEquals("Poll: Meet?", ChatFeatures.preview(ChatFeatures.encode(
            ChatContent.Poll("Meet?", listOf("Yes", "No")),
        )))
    }

    @Test fun onlyTheOriginalSenderCanEditOrDelete() {
        val original = message("one", "alice", "Hello")
        val invalidEdit = message("two", "bob", ChatFeatures.encode(ChatContent.Edit("one", "Tampered")))
        val validEdit = message("three", "alice", ChatFeatures.encode(ChatContent.Edit("one", "Updated")))
        val invalidDelete = message("four", "bob", ChatFeatures.encode(ChatContent.Delete("one")))
        val shown = ChatTimeline.present(listOf(original, invalidEdit, validEdit, invalidDelete)).single()
        assertEquals(ChatContent.Text("Updated"), shown.content)
        assertTrue(shown.edited)
        assertFalse(shown.deleted)
        assertTrue(ChatTimeline.present(listOf(original, message("five", "alice",
            ChatFeatures.encode(ChatContent.Delete("one"))))).single().deleted)
    }

    @Test fun votesCountLatestChoicePerSender() {
        val poll = message("poll", "alice", ChatFeatures.encode(ChatContent.Poll(
            "Choose", listOf("A", "B"),
        )))
        val first = message("vote1", "bob", ChatFeatures.encode(ChatContent.Vote("poll", 0)))
        val changed = message("vote2", "bob", ChatFeatures.encode(ChatContent.Vote("poll", 1)))
        val other = message("vote3", "carol", ChatFeatures.encode(ChatContent.Vote("poll", 1)))
        val shown = ChatTimeline.present(listOf(poll, first, changed, other)).single()
        assertEquals(listOf(0, 2), shown.votes)
    }

    private fun message(id: String, sender: String, text: String) = ChatMessage(
        id = id, peerId = "chat", text = text, author = MessageAuthor.PEER,
        sentAt = when (id) {
            "one", "poll" -> 1L
            "two", "vote1" -> 2L
            "three", "vote2" -> 3L
            "four", "vote3" -> 4L
            else -> 5L
        }, status = MessageStatus.DELIVERED, senderId = sender,
    )
}
