package com.example.blap.event

import com.example.blap.chat.IdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAnnouncementSyncTest {
    @Test fun openingEventRestoresCheckInAndCloudRoleChangesDoNotEraseIt() = withFixture { fixture ->
        fixture.remote.members = listOf(fixture.membership.copy(role = EventRole.ATTENDEE,
            accessMethod = null, checkedInAt = null))
        fixture.lifecycle.openEvent("event")
        assertEquals(EventPage.DETAIL, fixture.state.page)
        assertEquals("event", fixture.state.activeEventId)
        assertEquals(EventRole.ATTENDEE, fixture.state.membership!!.role)
        assertEquals(150L, fixture.state.membership!!.checkedInAt)
        assertEquals(EventAccessMethod.GPS, fixture.store.getMembership("event", "alice")!!.accessMethod)
        fixture.lifecycle.showAnnouncements()
        assertEquals(listOf("event"), fixture.mesh.refreshes)
    }

    @Test fun removedMembershipCannotKeepItsCachedVenueCheckIn() = withFixture { fixture ->
        fixture.remote.members = emptyList()
        fixture.lifecycle.openEvent("event")
        assertEquals(EventPage.LIST, fixture.state.page)
        assertEquals(null, fixture.state.activeEventId)
        assertEquals(null, fixture.store.getMembership("event", "alice"))
    }

    @Test fun gpsCheckInStaysOnDetailsAndMakesAnnouncementsAvailableToMesh() = withFixture { fixture ->
        val notCheckedIn = fixture.membership.copy(accessMethod = null, checkedInAt = null)
        fixture.store.saveMembership(notCheckedIn)
        fixture.state = fixture.state.copy(page = EventPage.DETAIL, activeEventId = null, membership = notCheckedIn)
        fixture.onSite.enterWithGps(0.0, 0.0, 5.0)
        assertEquals(EventPage.DETAIL, fixture.state.page)
        assertEquals("event", fixture.state.activeEventId)
        assertEquals(200L, fixture.store.getMembership("event", "alice")!!.checkedInAt)
        fixture.lifecycle.showAnnouncements()
        assertEquals(listOf("event"), fixture.mesh.refreshes)
    }

    @Test fun openingAnnouncementsRefreshesMeshEvenWhenCloudFetchFails() = withFixture { fixture ->
        fixture.remote.offline = true
        fixture.lifecycle.showAnnouncements()
        assertEquals(EventPage.ANNOUNCEMENTS, fixture.state.page)
        assertEquals(listOf("event"), fixture.mesh.refreshes)
    }

    @Test fun checkInCompletingOnAnnouncementsActivatesMeshAndReplaysHistory() = withFixture { fixture ->
        val notCheckedIn = fixture.membership.copy(accessMethod = null, checkedInAt = null)
        fixture.store.saveMembership(notCheckedIn)
        fixture.state = fixture.state.copy(page = EventPage.ANNOUNCEMENTS, activeEventId = null, membership = notCheckedIn)
        val announcement = fixture.announcement()
        fixture.store.saveAnnouncement(announcement)
        fixture.remote.offline = true
        fixture.onSite.enterWithGps(0.0, 0.0, 5.0)

        assertEquals(EventPage.ANNOUNCEMENTS, fixture.state.page)
        assertEquals(listOf("event"), fixture.mesh.activations)
        assertEquals("event", fixture.state.activeEventId)
        fixture.onSite.onPeerAvailable("bob-peer", "event", "bob", accessGranted = true)
        assertEquals(listOf("bob-peer" to listOf(announcement)), fixture.mesh.history)
    }

    @Test fun viewingAnnouncementsWithoutCheckInDoesNotStartMeshDiscovery() = withFixture { fixture ->
        fixture.state = fixture.state.copy(activeEventId = null)
        fixture.lifecycle.showAnnouncements()
        assertTrue(fixture.mesh.refreshes.isEmpty())
    }

    @Test fun offlinePublishIsSignedAndRefreshesMeshWithoutClaimingDelivery() = withFixture { fixture ->
        fixture.remote.offline = true
        fixture.lifecycle.publishAnnouncement("Gate changed")
        assertEquals(listOf("event"), fixture.mesh.refreshes)
        val announcement = fixture.mesh.sent.single()
        assertTrue(EventAnnouncementSigner.verify(announcement, fixture.keys.public))
        assertEquals(announcement.id, fixture.store.getAnnouncements("event").single().id)
        assertTrue(fixture.state.notice.orEmpty().contains("saved locally"))
        assertFalse(fixture.state.notice.orEmpty().contains("sent on-site"))
    }

    @Test fun cloudUpdatesRelayNewAnnouncementsButNotDuplicateSnapshots() = withFixture { fixture ->
        fixture.lifecycle.showAnnouncements()
        val announcement = fixture.announcement()
        fixture.remote.onAnnouncements?.invoke(listOf(announcement))
        fixture.remote.onAnnouncements?.invoke(listOf(announcement))
        assertEquals(listOf(announcement), fixture.mesh.sent)
        assertEquals(listOf(announcement), fixture.state.announcements)
    }

    @Test fun fetchedAnnouncementsRelayOnceAndKeepTheirSignature() = withFixture { fixture ->
        fixture.remote.announcements = listOf(fixture.announcement())
        fixture.lifecycle.showAnnouncements()
        fixture.lifecycle.showAnnouncements()
        assertEquals(fixture.remote.announcements, fixture.mesh.sent)
    }

    @Test fun cloudAnnouncementsStayLocalWithoutAnActiveCheckedInEvent() = withFixture { fixture ->
        fixture.state = fixture.state.copy(activeEventId = null)
        fixture.remote.announcements = listOf(fixture.announcement())
        fixture.lifecycle.showAnnouncements()
        assertTrue(fixture.mesh.sent.isEmpty())
        assertEquals(fixture.remote.announcements, fixture.state.announcements)
    }

    @Test fun peerArrivalReplaysHistoryWhileAnnouncementsAreOpen() = withFixture { fixture ->
        val announcement = fixture.announcement()
        fixture.store.saveAnnouncement(announcement)
        fixture.state = fixture.state.copy(page = EventPage.ANNOUNCEMENTS)
        fixture.onSite.onPeerAvailable("bob-peer", "event", "bob", accessGranted = true)
        assertEquals(listOf("bob-peer" to listOf(announcement)), fixture.mesh.history)
        fixture.onSite.onPeerAvailable("outsider-peer", "event", "outsider", accessGranted = true)
        fixture.onSite.onPeerAvailable("bob-peer", "other", "bob", accessGranted = true)
        assertEquals(1, fixture.mesh.history.size)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.scope.cancel() }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keys = EventCheckInCodec.generateAdminKeyPair()
        val event = CommunityEvent("event", "Meetup", "", latitude = 0.0, longitude = 0.0,
            startsAt = 100L, endsAt = 300L, createdBy = "alice", memberIds = setOf("alice", "bob"),
            adminPublicKeys = mapOf("alice" to EventCheckInCodec.encodePublicKey(keys.public)),
            visibility = EventVisibility.PRIVATE, privateMeshSecret = "secret")
        val membership = EventMembership("event", "alice", "Alice", EventRole.PRIMARY_ADMIN, 100L,
            accessMethod = EventAccessMethod.GPS, checkedInAt = 150L)
        var state = EventUiState(events = listOf(event), selectedEventId = "event", activeEventId = "event",
            membership = membership)
        val store = InMemoryEventStore().apply { saveEvent(event); saveMembership(membership) }
        val remote = FakeRemote().apply { members = listOf(membership) }
        val mesh = RecordingMesh()
        private val keyStore = object : EventAdminKeyStore {
            override fun getOrCreate(userId: String) = keys
            override fun get(userId: String) = keys
        }
        private val identity = object : IdentityStore {
            override fun getPeerId() = "alice-peer"
            override fun getDisplayName() = "Alice"
            override fun saveDisplayName(name: String) = Unit
            override fun getPhoneNumber() = ""
            override fun savePhoneNumber(phoneNumber: String) = Unit
        }
        val lifecycle = EventLifecycleCoordinator(store, remote, keyStore, identity, mesh, scope,
            { state }, { transform -> state = transform(state) }, {}, { 200L })
        val onSite = EventOnSiteCoordinator(store, remote, keyStore, mesh, NoopEventNotifier, scope,
            { state }, { transform -> state = transform(state) }, {}, { 200L })

        fun announcement(): EventAnnouncement {
            val unsigned = EventAnnouncement(id = "announcement", eventId = "event", adminId = "alice",
                adminName = "Alice", text = "Gate changed", createdAt = 200L, syncedToCloud = true)
            return unsigned.copy(signature = EventAnnouncementSigner.sign(unsigned, keys.private))
        }
    }

    private class RecordingMesh : EventMeshGateway {
        val activations = mutableListOf<String?>()
        val refreshes = mutableListOf<String>()
        val sent = mutableListOf<EventAnnouncement>()
        val history = mutableListOf<Pair<String, List<EventAnnouncement>>>()
        override fun setActiveEvent(eventId: String?, meshSecret: String, userId: String, accessGranted: Boolean) {
            activations += eventId
        }
        override fun refreshEventMesh(eventId: String) { refreshes += eventId }
        override fun sendEventAnnouncement(announcement: EventAnnouncement) { sent += announcement }
        override fun synchronizeEventAnnouncements(peerId: String, announcements: List<EventAnnouncement>) {
            history += peerId to announcements
        }
    }

    /** Only announcement methods are expected in these coordinator tests. */
    private class FakeRemote : EventRemoteRepository {
        var offline = false
        var announcements = emptyList<EventAnnouncement>()
        var members = emptyList<EventMembership>()
        var onAnnouncements: ((List<EventAnnouncement>) -> Unit)? = null
        override suspend fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> {
            check(!offline) { "No internet" }
            return announcements
        }
        override suspend fun saveAnnouncement(announcement: EventAnnouncement) { check(!offline) { "No internet" } }
        override fun observeAnnouncements(eventId: String, limit: Int,
            onAnnouncements: (List<EventAnnouncement>) -> Unit, onError: (Throwable) -> Unit): AutoCloseable {
            this.onAnnouncements = onAnnouncements
            return AutoCloseable { this.onAnnouncements = null }
        }
        override suspend fun requireUserId() = "alice"
        override fun hasSignedInAccount() = true
        override suspend fun createEvent(event: CommunityEvent, creator: EventMembership) = unused()
        override suspend fun listEvents(): List<CommunityEvent> = unused()
        override suspend fun updateEvent(event: CommunityEvent) = unused()
        override suspend fun deleteEvent(eventId: String) = unused()
        override suspend fun listInvitations(): List<EventInvitation> = unused()
        override suspend fun listEventInvitations(eventId: String): List<EventInvitation> = unused()
        override suspend fun findInvitee(identifier: String): EventInvitee? = unused()
        override suspend fun invite(invitation: EventInvitation) = unused()
        override suspend fun acceptInvitation(invitation: EventInvitation, membership: EventMembership): CommunityEvent? = unused()
        override suspend fun declineInvitation(invitation: EventInvitation) = unused()
        override suspend fun revokeInvitation(invitation: EventInvitation) = unused()
        override suspend fun joinEvent(membership: EventMembership) = unused()
        override suspend fun leaveEvent(event: CommunityEvent, membership: EventMembership, leftAt: Long) = unused()
        override suspend fun listMembers(eventId: String): List<EventMembership> = members
        override suspend fun promoteToCoAdmin(eventId: String, userId: String) = unused()
        override suspend fun blockMember(event: CommunityEvent, userId: String, blockedAt: Long) = unused()
        override suspend fun registerAdminPublicKey(eventId: String, userId: String, encodedPublicKey: String) = unused()
        override fun observeDiscussionRoots(eventId: String, limit: Int, onComments: (List<EventDiscussionComment>) -> Unit,
            onError: (Throwable) -> Unit): AutoCloseable? = unused()
        override fun observeDiscussionThread(eventId: String, threadId: String, onComments: (List<EventDiscussionComment>) -> Unit,
            onError: (Throwable) -> Unit): AutoCloseable? = unused()
        override fun observeDiscussionLikes(eventId: String, onLikedCommentIds: (Set<String>) -> Unit,
            onError: (Throwable) -> Unit): AutoCloseable? = unused()
        override suspend fun createDiscussionComment(comment: EventDiscussionComment) = unused()
        override suspend fun setDiscussionLike(comment: EventDiscussionComment, liked: Boolean) = unused()
        override suspend fun deleteOwnDiscussionComment(comment: EventDiscussionComment, deletedAt: Long) = unused()
        override suspend fun deleteDiscussionBranchAsAdmin(comment: EventDiscussionComment, deletedAt: Long) = unused()
        private fun unused(): Nothing = error("Unexpected repository call")
    }
}
