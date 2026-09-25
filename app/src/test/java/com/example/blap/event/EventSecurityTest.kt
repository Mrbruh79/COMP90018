package com.example.blap.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSecurityTest {
    @Test
    fun announcementSignatureCoversMessageAndRevision() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val announcement = EventAnnouncement(
            id = "announcement-1",
            eventId = "event-1",
            adminId = "admin-1",
            adminName = "Organiser",
            text = "Doors open at six",
            createdAt = 100L,
            revision = 100L,
        )
        val signed = announcement.copy(
            signature = EventAnnouncementSigner.sign(announcement, keys.private),
        )

        assertTrue(EventAnnouncementSigner.verify(signed, keys.public))
        assertFalse(EventAnnouncementSigner.verify(signed.copy(text = "Doors open at seven"), keys.public))
        assertFalse(EventAnnouncementSigner.verify(signed.copy(revision = 101L), keys.public))
    }

    @Test
    fun eventMutationSignatureCoversEditsAndDeletion() {
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val event = CommunityEvent(
            id = "event-1",
            title = "Original title",
            description = "Description",
            venueName = "Venue",
            latitude = -37.8136,
            longitude = 144.9631,
            startsAt = 1_000L,
            endsAt = 2_000L,
            createdBy = "admin-1",
            adminPublicKeys = mapOf("admin-1" to EventCheckInCodec.encodePublicKey(keys.public)),
            createdAt = 100L,
            updatedAt = 200L,
        )
        val mutation = EventMutation(event, "admin-1")
        val signed = mutation.copy(signature = EventMutationSigner.sign(mutation, keys.private))

        assertTrue(EventMutationSigner.verify(signed, keys.public))
        assertFalse(EventMutationSigner.verify(signed.copy(event = event.copy(title = "Tampered")), keys.public))
        assertFalse(EventMutationSigner.verify(signed.copy(event = event.copy(deletedAt = 201L)), keys.public))
    }
}
