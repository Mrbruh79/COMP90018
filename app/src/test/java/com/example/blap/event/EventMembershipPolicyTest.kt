package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EventMembershipPolicyTest {
    private val now = 1_000_000L
    private val publicEvent = CommunityEvent(
        id = "event-1",
        title = "Public event",
        description = "Membership policy test",
        latitude = -37.8136,
        longitude = 144.9631,
        startsAt = now + 1_000,
        endsAt = now + 10_000,
        createdBy = "admin-1",
        adminIds = setOf("admin-1"),
        memberIds = setOf("admin-1"),
        createdAt = now,
        updatedAt = now,
    )
    private val privateEvent = publicEvent.copy(
        id = "private-event",
        title = "Private event",
        visibility = EventVisibility.PRIVATE,
        privateMeshSecret = "private-secret",
    )
    private val primaryAdmin = EventMembership(
        eventId = privateEvent.id,
        userId = "admin-1",
        displayName = "Admin",
        role = EventRole.PRIMARY_ADMIN,
        joinedAt = now,
    )
    private val attendee = EventMembership(
        eventId = privateEvent.id,
        userId = "member-1",
        displayName = "Member",
        role = EventRole.ATTENDEE,
        joinedAt = now,
    )

    @Test
    fun publicEventCanBeJoinedWithoutAccountUnlessProtected() {
        assertNull(EventMembershipPolicy.joinError(publicEvent, hasSignedInAccount = false))
        assertEquals(
            "Sign in with Email or Google to join this protected event.",
            EventMembershipPolicy.joinError(
                publicEvent.copy(requiresSignIn = true),
                hasSignedInAccount = false,
            ),
        )
        assertNull(
            EventMembershipPolicy.joinError(
                publicEvent.copy(requiresSignIn = true),
                hasSignedInAccount = true,
            ),
        )
    }

    @Test
    fun privateAndDeletedEventsCannotUsePublicJoinFlow() {
        assertEquals(
            "Private events can only be joined by accepting an invitation.",
            EventMembershipPolicy.joinError(privateEvent, hasSignedInAccount = true),
        )
        assertEquals(
            "This event has been deleted by its admin.",
            EventMembershipPolicy.joinError(
                publicEvent.copy(deletedAt = now + 1),
                hasSignedInAccount = true,
            ),
        )
    }

    @Test
    fun rejoiningPreservesRoleAndOriginalJoinTimeButClearsInactiveState() {
        val existing = attendee.copy(
            role = EventRole.CO_ADMIN,
            joinedAt = now - 5_000,
            leftAt = now - 1_000,
            blockedAt = now - 2_000,
            accessMethod = EventAccessMethod.GPS,
            checkedInAt = now - 3_000,
        )

        val rejoined = EventMembershipPolicy.joinedMembership(
            event = publicEvent,
            userId = existing.userId,
            displayName = "Updated name",
            existing = existing,
            joinedAt = now,
        )

        assertEquals(EventRole.CO_ADMIN, rejoined.role)
        assertEquals(now - 5_000, rejoined.joinedAt)
        assertEquals("Updated name", rejoined.displayName)
        assertNull(rejoined.leftAt)
        assertNull(rejoined.blockedAt)
        assertNull(rejoined.accessMethod)
        assertNull(rejoined.checkedInAt)
    }

    @Test
    fun onlyListedPrivateEventAdminsCanInvite() {
        assertNull(EventMembershipPolicy.invitationError(privateEvent, primaryAdmin))
        assertEquals(
            "Only a private-event admin can send invitations.",
            EventMembershipPolicy.invitationError(privateEvent, attendee),
        )
        assertEquals(
            "Only a private-event admin can send invitations.",
            EventMembershipPolicy.invitationError(publicEvent, primaryAdmin.copy(eventId = publicEvent.id)),
        )
    }

    @Test
    fun invitationIsBoundToEventInviterRecipientAndEventExpiry() {
        val invitee = EventInvitee("member-2", "Alice", "alice")

        val invitation = EventMembershipPolicy.invitation(privateEvent, primaryAdmin, invitee, now)

        assertEquals("${privateEvent.id}_${invitee.uid}", invitation.id)
        assertEquals(privateEvent.id, invitation.eventId)
        assertEquals(primaryAdmin.userId, invitation.inviterUid)
        assertEquals(invitee.uid, invitation.recipientUid)
        assertEquals(privateEvent.endsAt, invitation.expiresAt)
        assertEquals(EventInvitationStatus.PENDING, invitation.status)
    }

    @Test
    fun onlyPrimaryAdminCanPromoteCoAdmins() {
        assertNull(EventMembershipPolicy.promotionError(privateEvent, primaryAdmin))
        assertEquals(
            "Only the primary admin can appoint co-admins.",
            EventMembershipPolicy.promotionError(privateEvent, attendee.copy(role = EventRole.CO_ADMIN)),
        )
    }

    @Test
    fun adminsCannotRemovePrimaryAdminOrThemselves() {
        assertEquals(
            "The primary admin cannot be removed.",
            EventMembershipPolicy.removalError(primaryAdmin, primaryAdmin),
        )
        val coAdmin = attendee.copy(userId = "co-admin-1", role = EventRole.CO_ADMIN)
        assertEquals(
            "The primary admin cannot be removed.",
            EventMembershipPolicy.removalError(coAdmin, coAdmin),
        )
        assertNull(EventMembershipPolicy.removalError(primaryAdmin, attendee))
        assertFalse(EventMembershipPolicy.removalAdminError(privateEvent, attendee) == null)
    }
}
