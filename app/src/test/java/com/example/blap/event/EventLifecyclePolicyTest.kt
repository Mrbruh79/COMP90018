package com.example.blap.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLifecyclePolicyTest {
    private val now = 1_000_000L
    private val keys = EventCheckInCodec.generateAdminKeyPair()
    private val request = EventCreateRequest(
        title = "  CommonGround Live  ",
        description = "  Event description  ",
        venueName = "  Testing Hall  ",
        latitude = -37.8136,
        longitude = 144.9631,
        radiusMetres = 100.0,
        startsAt = now + 1_000,
        endsAt = now + 10_000,
        visibility = EventVisibility.PUBLIC,
        requiresSignIn = true,
    )

    @Test
    fun creationRequiresTitleFutureEndTimeAndSignedInAccount() {
        assertEquals(
            "Enter an event title.",
            EventLifecyclePolicy.createError(request.copy(title = "  "), now, true),
        )
        assertEquals(
            "Choose an end time after the start time.",
            EventLifecyclePolicy.createError(request.copy(endsAt = request.startsAt), now, true),
        )
        assertEquals(
            "Sign in with Email or Google to create an event.",
            EventLifecyclePolicy.createError(request, now, false),
        )
        assertNull(EventLifecyclePolicy.createError(request, now, true))
    }

    @Test
    fun publicCreationBuildsPrimaryAdminAndSignedStaticQr() {
        val creation = EventLifecyclePolicy.createModels(
            request = request,
            eventId = "event-1",
            userId = "admin-1",
            displayName = "Admin",
            keys = keys,
            createdAt = now,
            joinedAt = now + 1,
            privateMeshSecret = "",
        )

        assertEquals("CommonGround Live", creation.event.title)
        assertEquals("Event description", creation.event.description)
        assertEquals("Testing Hall", creation.event.venueName)
        assertTrue(creation.event.requiresSignIn)
        assertTrue(creation.event.privateMeshSecret.isBlank())
        assertNotNull(
            EventCheckInCodec.verify(
                creation.event.venueCheckInPayload,
                creation.event.id,
                keys.public,
            ),
        )
        assertEquals(EventRole.PRIMARY_ADMIN, creation.membership.role)
        assertEquals(now + 1, creation.membership.joinedAt)
        assertEquals(setOf("admin-1"), creation.event.memberIds)
    }

    @Test
    fun privateCreationUsesMeshSecretAndNeverCreatesVenueQr() {
        val creation = EventLifecyclePolicy.createModels(
            request = request.copy(visibility = EventVisibility.PRIVATE, requiresSignIn = true),
            eventId = "private-event",
            userId = "admin-1",
            displayName = "Admin",
            keys = keys,
            createdAt = now,
            joinedAt = now,
            privateMeshSecret = "mesh-secret",
        )

        assertEquals(EventVisibility.PRIVATE, creation.event.visibility)
        assertEquals("mesh-secret", creation.event.privateMeshSecret)
        assertTrue(creation.event.venueCheckInPayload.isBlank())
        assertFalse(creation.event.requiresSignIn)
    }

    @Test
    fun updatePreservesSecurityFieldsAndUsesMonotonicRevision() {
        val original = privateEvent(updatedAt = now + 500)
        val update = request.copy(
            title = " Updated title ",
            visibility = EventVisibility.PUBLIC,
            requiresSignIn = true,
        )

        val edited = EventLifecyclePolicy.updatedEvent(original, update, now)

        assertEquals("Updated title", edited.title)
        assertEquals(EventVisibility.PRIVATE, edited.visibility)
        assertEquals(original.privateMeshSecret, edited.privateMeshSecret)
        assertEquals(original.memberIds, edited.memberIds)
        assertEquals(original.adminIds, edited.adminIds)
        assertFalse(edited.requiresSignIn)
        assertEquals(original.updatedAt + 1, edited.updatedAt)
    }

    @Test
    fun deletionKeepsOriginalDeletionTimeAndAdvancesRevision() {
        val event = publicEvent(updatedAt = now + 100)
        val deleted = EventLifecyclePolicy.deletedEvent(event, now)
        val repeated = EventLifecyclePolicy.deletedEvent(deleted, now + 500)

        assertEquals(now, deleted.deletedAt)
        assertEquals(now + 101, deleted.updatedAt)
        assertEquals(deleted.deletedAt, repeated.deletedAt)
        assertEquals(now + 500, repeated.updatedAt)
    }

    @Test
    fun editDeleteAndAnnouncementPermissionsRemainSeparate() {
        val event = publicEvent()
        val primary = membership(event.id, "admin-1", EventRole.PRIMARY_ADMIN)
        val coAdmin = membership(event.id, "co-admin-1", EventRole.CO_ADMIN)
        val attendee = membership(event.id, "member-1", EventRole.ATTENDEE)
        val eventWithCoAdmin = event.copy(
            adminIds = event.adminIds + coAdmin.userId,
            memberIds = event.memberIds + coAdmin.userId + attendee.userId,
        )

        assertNull(EventLifecyclePolicy.editPermissionError(eventWithCoAdmin, coAdmin))
        assertNull(EventLifecyclePolicy.announcementError(eventWithCoAdmin, coAdmin))
        assertEquals(
            "Only the primary admin can delete this event.",
            EventLifecyclePolicy.deletePermissionError(eventWithCoAdmin, coAdmin),
        )
        assertNull(EventLifecyclePolicy.deletePermissionError(eventWithCoAdmin, primary))
        assertEquals(
            "Only event admins can post announcements.",
            EventLifecyclePolicy.announcementError(eventWithCoAdmin, attendee),
        )
    }

    @Test
    fun nonAuthoritativeSnapshotKeepsMissingJoinedEventAsOfflineFallback() {
        val first = publicEvent(id = "event-1")
        val second = publicEvent(id = "event-2")

        val result = EventLifecyclePolicy.reconcileEvents(
            localEvents = listOf(first, second),
            remoteEvents = listOf(first.copy(title = "Updated")),
            authoritative = false,
            currentUserId = "admin-1",
        )

        assertTrue(result.removedEventIds.isEmpty())
        assertEquals(listOf("event-1", "event-2"), result.visibleEvents.map(CommunityEvent::id))
        assertEquals(listOf("event-1"), result.cacheableEvents.map(CommunityEvent::id))
        assertTrue(result.showingOfflineEvents)
    }

    @Test
    fun tombstonesAlwaysRemoveWhileAuthoritativeSnapshotRemovesMissingPackages() {
        val first = publicEvent(id = "event-1")
        val second = publicEvent(id = "event-2")
        val tombstone = second.copy(deletedAt = now + 1, updatedAt = now + 1)

        val cached = EventLifecyclePolicy.reconcileEvents(
            localEvents = listOf(first, second),
            remoteEvents = listOf(first, tombstone),
            authoritative = false,
            currentUserId = "admin-1",
        )
        val authoritative = EventLifecyclePolicy.reconcileEvents(
            localEvents = listOf(first, second),
            remoteEvents = listOf(first),
            authoritative = true,
            currentUserId = "admin-1",
        )

        assertEquals(setOf("event-2"), cached.removedEventIds)
        assertEquals(listOf("event-2"), cached.tombstones.map(CommunityEvent::id))
        assertEquals(setOf("event-2"), authoritative.removedEventIds)
    }

    @Test
    fun publicCatalogueEventIsVisibleButNotCachedBeforeJoining() {
        val publicEvent = publicEvent().copy(memberIds = setOf("admin-1"))

        val result = EventLifecyclePolicy.reconcileEvents(
            localEvents = emptyList(),
            remoteEvents = listOf(publicEvent),
            authoritative = true,
            currentUserId = "member-1",
        )

        assertEquals(listOf(publicEvent), result.visibleEvents)
        assertTrue(result.cacheableEvents.isEmpty())
        assertTrue(result.removedEventIds.isEmpty())
    }

    @Test
    fun cachedFirestoreSnapshotHidesPublicEventsThatWereNeverJoined() {
        val publicEvent = publicEvent().copy(memberIds = setOf("admin-1"))

        val result = EventLifecyclePolicy.reconcileEvents(
            localEvents = emptyList(),
            remoteEvents = listOf(publicEvent),
            authoritative = false,
            currentUserId = "member-1",
        )

        assertTrue(result.visibleEvents.isEmpty())
        assertTrue(result.cacheableEvents.isEmpty())
        assertFalse(result.showingOfflineEvents)
    }

    @Test
    fun confirmedJoinedEventIsIncludedInOfflinePackageSet() {
        val joinedEvent = publicEvent().copy(memberIds = setOf("admin-1", "member-1"))

        val result = EventLifecyclePolicy.reconcileEvents(
            localEvents = emptyList(),
            remoteEvents = listOf(joinedEvent),
            authoritative = true,
            currentUserId = "member-1",
        )

        assertEquals(listOf(joinedEvent), result.cacheableEvents)
    }

    @Test
    fun authoritativeMembershipRemovalPurgesPackageButKeepsPublicEventVisible() {
        val cachedJoinedEvent = publicEvent().copy(memberIds = setOf("admin-1", "member-1"))
        val remoteWithoutMember = cachedJoinedEvent.copy(memberIds = setOf("admin-1"))

        val result = EventLifecyclePolicy.reconcileEvents(
            localEvents = listOf(cachedJoinedEvent),
            remoteEvents = listOf(remoteWithoutMember),
            authoritative = true,
            currentUserId = "member-1",
        )

        assertEquals(listOf(remoteWithoutMember), result.visibleEvents)
        assertTrue(result.cacheableEvents.isEmpty())
        assertEquals(setOf(cachedJoinedEvent.id), result.removedEventIds)
        assertFalse(result.showingOfflineEvents)
    }

    private fun publicEvent(id: String = "event-1", updatedAt: Long = now): CommunityEvent = CommunityEvent(
        id = id,
        title = "Event",
        description = "Description",
        latitude = -37.8136,
        longitude = 144.9631,
        startsAt = now - 1_000,
        endsAt = now + 10_000,
        createdBy = "admin-1",
        adminIds = setOf("admin-1"),
        memberIds = setOf("admin-1"),
        createdAt = now - 2_000,
        updatedAt = updatedAt,
    )

    private fun privateEvent(updatedAt: Long = now): CommunityEvent = publicEvent(updatedAt = updatedAt).copy(
        visibility = EventVisibility.PRIVATE,
        privateMeshSecret = "original-secret",
        venueCheckInPayload = "",
    )

    private fun membership(eventId: String, userId: String, role: EventRole): EventMembership = EventMembership(
        eventId = eventId,
        userId = userId,
        displayName = userId,
        role = role,
        joinedAt = now,
    )
}
