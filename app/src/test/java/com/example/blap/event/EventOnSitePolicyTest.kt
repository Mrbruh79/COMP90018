package com.example.blap.event

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventOnSitePolicyTest {
    private val now = 1_000_000L
    private val adminKeys = EventCheckInCodec.generateAdminKeyPair()
    private val privateEvent = CommunityEvent(
        id = "event-1",
        title = "Private meetup",
        description = "Policy test",
        latitude = -37.8136,
        longitude = 144.9631,
        startsAt = now - 1_000,
        endsAt = now + 1_000,
        createdBy = "admin-1",
        adminIds = setOf("admin-1", "co-admin-1"),
        memberIds = setOf("admin-1", "co-admin-1", "member-1"),
        adminPublicKeys = mapOf(
            "admin-1" to EventCheckInCodec.encodePublicKey(adminKeys.public),
        ),
        visibility = EventVisibility.PRIVATE,
        privateMeshSecret = "private-secret",
        createdAt = now - 5_000,
        updatedAt = now - 500,
    )
    private val adminMembership = EventMembership(
        eventId = privateEvent.id,
        userId = "admin-1",
        displayName = "Admin",
        role = EventRole.PRIMARY_ADMIN,
        joinedAt = now - 5_000,
    )
    private val attendeeMembership = EventMembership(
        eventId = privateEvent.id,
        userId = "member-1",
        displayName = "Member",
        role = EventRole.ATTENDEE,
        joinedAt = now - 4_000,
    )

    @Test
    fun privatePeerMustBeAnApprovedMemberBeforeHistorySync() {
        assertTrue(EventOnSitePolicy.canSynchronizePeer(privateEvent, privateEvent.id, "member-1", true))
        assertFalse(EventOnSitePolicy.canSynchronizePeer(privateEvent, privateEvent.id, "outsider", true))
        assertFalse(EventOnSitePolicy.canSynchronizePeer(privateEvent, privateEvent.id, "member-1", false))
        assertFalse(EventOnSitePolicy.canSynchronizePeer(privateEvent, "other-event", "member-1", true))
    }


    @Test
    fun chatMessageRequiresActiveEventAndKnownActiveSender() {
        val message = EventChatMessage(
            id = "message-1",
            eventId = privateEvent.id,
            senderId = "member-1",
            senderName = "Member",
            text = "Hello",
            createdAt = now,
        )

        assertTrue(
            EventOnSitePolicy.canAcceptChatMessage(
                privateEvent,
                attendeeMembership,
                listOf(adminMembership, attendeeMembership),
                privateEvent.id,
                message,
                now,
            ),
        )
        assertFalse(
            EventOnSitePolicy.canAcceptChatMessage(
                privateEvent,
                attendeeMembership,
                listOf(adminMembership),
                privateEvent.id,
                message,
                now,
            ),
        )
        assertFalse(
            EventOnSitePolicy.canAcceptChatMessage(
                privateEvent,
                attendeeMembership,
                emptyList(),
                "other-event",
                message,
                now,
            ),
        )
    }

    @Test
    fun signedAdminAnnouncementIsAcceptedButTamperingIsRejected() {
        val unsigned = EventAnnouncement(
            id = "announcement-1",
            eventId = privateEvent.id,
            adminId = "admin-1",
            adminName = "Admin",
            text = "Doors open now",
            createdAt = now,
        )
        val announcement = unsigned.copy(
            signature = EventAnnouncementSigner.sign(unsigned, adminKeys.private),
        )

        assertTrue(EventOnSitePolicy.isValidAnnouncement(privateEvent, announcement))
        assertFalse(EventOnSitePolicy.isValidAnnouncement(privateEvent, announcement.copy(text = "Tampered")))
    }

    @Test
    fun signedNewerMutationIsAcceptedAndProtectedFieldsRemainLocal() {
        val edited = privateEvent.copy(
            title = "Updated title",
            updatedAt = privateEvent.updatedAt + 1,
        )
        val unsigned = EventMutation(edited, "admin-1")
        val mutation = unsigned.copy(signature = EventMutationSigner.sign(unsigned, adminKeys.private))

        val accepted = EventOnSitePolicy.verifiedMutationEvent(privateEvent, mutation)

        assertTrue(accepted?.title == "Updated title")
        assertTrue(accepted?.memberIds == privateEvent.memberIds)
        assertTrue(accepted?.visibility == privateEvent.visibility)
        assertTrue(accepted?.privateMeshSecret == privateEvent.privateMeshSecret)
    }

    @Test
    fun staleTamperedAndCoAdminDeletionMutationsAreRejected() {
        val stale = privateEvent.copy(title = "Stale", updatedAt = privateEvent.updatedAt)
        val staleUnsigned = EventMutation(stale, "admin-1")
        val staleMutation = staleUnsigned.copy(signature = EventMutationSigner.sign(staleUnsigned, adminKeys.private))

        val edited = privateEvent.copy(title = "Updated", updatedAt = privateEvent.updatedAt + 1)
        val validUnsigned = EventMutation(edited, "admin-1")
        val tampered = validUnsigned.copy(
            signature = EventMutationSigner.sign(validUnsigned, adminKeys.private),
            event = edited.copy(title = "Tampered after signing"),
        )
        val coAdminDeletion = EventMutation(
            event = privateEvent.copy(deletedAt = now, updatedAt = privateEvent.updatedAt + 1),
            adminId = "co-admin-1",
            signature = "not-relevant",
        )

        assertNull(EventOnSitePolicy.verifiedMutationEvent(privateEvent, staleMutation))
        assertNull(EventOnSitePolicy.verifiedMutationEvent(privateEvent, tampered))
        assertNull(EventOnSitePolicy.verifiedMutationEvent(privateEvent, coAdminDeletion))
    }
}
