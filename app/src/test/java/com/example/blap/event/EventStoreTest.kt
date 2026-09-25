package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStoreTest {
    @Test
    fun eventChatIsDeduplicatedAndLimitedToLatestFifty() {
        val store = InMemoryEventStore()
        repeat(60) { index ->
            assertTrue(
                store.saveChatMessage(
                    EventChatMessage(
                        id = "message-$index",
                        eventId = "event-1",
                        senderId = "alice",
                        senderName = "Alice",
                        text = "Message $index",
                        createdAt = index.toLong(),
                    ),
                ),
            )
        }

        assertFalse(
            store.saveChatMessage(
                EventChatMessage("message-59", "event-1", "alice", "Alice", "Duplicate", 59L),
            ),
        )
        val recent = store.getRecentChatMessages("event-1", 50)
        assertEquals(50, recent.size)
        assertEquals("message-10", recent.first().id)
        assertEquals("message-59", recent.last().id)
        assertEquals(60, store.getChatMessages("event-1").size)
    }

    @Test
    fun deletingLocalEventDataDoesNotAffectOtherEvents() {
        val store = InMemoryEventStore()
        val first = event("event-1")
        val second = event("event-2")
        store.saveEvent(first)
        store.saveEvent(second)
        store.saveMembership(EventMembership(first.id, "user-1", "Alice", EventRole.ATTENDEE, 1L))
        store.saveChatMessage(EventChatMessage(eventId = first.id, senderId = "user-1", senderName = "Alice", text = "Hi"))

        store.deleteLocalEventData(first.id, "user-1")

        assertNull(store.getEvent(first.id))
        assertTrue(store.getRecentChatMessages(first.id).isEmpty())
        assertEquals(second, store.getEvent(second.id))
    }

    @Test
    fun purgingEventRemovesEveryMemberAnnouncementAndMessage() {
        val store = InMemoryEventStore()
        val deleted = event("event-1")
        val retained = event("event-2")
        store.saveEvent(deleted)
        store.saveEvent(retained)
        store.saveMembership(EventMembership(deleted.id, "admin", "Admin", EventRole.PRIMARY_ADMIN, 1L))
        store.saveMembership(EventMembership(deleted.id, "attendee", "Alice", EventRole.ATTENDEE, 1L))
        store.saveAnnouncement(
            EventAnnouncement(eventId = deleted.id, adminId = "admin", adminName = "Admin", text = "Update"),
        )
        store.saveChatMessage(
            EventChatMessage(eventId = deleted.id, senderId = "attendee", senderName = "Alice", text = "Hello"),
        )

        store.purgeEvent(deleted.id)

        assertNull(store.getEvent(deleted.id))
        assertNull(store.getMembership(deleted.id, "admin"))
        assertNull(store.getMembership(deleted.id, "attendee"))
        assertTrue(store.getAnnouncements(deleted.id).isEmpty())
        assertTrue(store.getChatMessages(deleted.id).isEmpty())
        assertEquals(retained, store.getEvent(retained.id))
    }

    private fun event(id: String) = CommunityEvent(
        id = id,
        title = id,
        description = "",
        latitude = -37.8,
        longitude = 144.9,
        startsAt = 1L,
        endsAt = 2L,
        createdBy = "admin",
    )
}
