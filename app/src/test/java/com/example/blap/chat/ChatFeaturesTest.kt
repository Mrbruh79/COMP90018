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

    @Test fun voiceNotesRoundTripAndPreviewAsVoiceMessage() {
        val voice = ChatContent.Voice(1_500, "YWJjMTIz")
        assertEquals(voice, ChatFeatures.decode(ChatFeatures.encode(voice)))
        assertEquals("Voice message", ChatFeatures.preview(ChatFeatures.encode(voice)))
        assertFalse(ChatFeatures.encode(voice).contains("+"))
        assertFalse(ChatFeatures.encode(voice).contains("/"))
    }

    @Test fun voiceEncodingStaysUrlSafeForBinaryAudio() {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { 0xFF.toByte() })
        val voice = ChatContent.Voice(1_000, encoded)
        val packed = ChatFeatures.encode(voice)
        assertFalse(packed.contains("+"))
        assertFalse(packed.contains("/"))
        assertEquals(voice, ChatFeatures.decode(packed))
    }

    @Test fun voiceNotesStayOnTheTimelineAndCannotBeEdited() {
        val voice = message("voice", "alice", ChatFeatures.encode(ChatContent.Voice(2_000, "YWJj")))
        val ignoredEdit = message("edit", "alice", ChatFeatures.encode(ChatContent.Edit("voice", "text")))
        val shown = ChatTimeline.present(listOf(voice, ignoredEdit)).single()
        assertEquals(ChatContent.Voice(2_000, "YWJj"), shown.content)
        assertFalse(shown.edited)
        assertTrue(ChatTimeline.present(listOf(voice, message("del", "alice",
            ChatFeatures.encode(ChatContent.Delete("voice"))))).single().deleted)
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
