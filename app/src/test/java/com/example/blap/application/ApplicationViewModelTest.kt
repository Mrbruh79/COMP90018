package com.example.blap.application

import androidx.lifecycle.ViewModelStore
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.DeviceContact
import com.example.blap.chat.NotificationSettingsRepository
import com.example.blap.event.EventPage
import com.example.blap.event.EventUiState
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventMembership
import com.example.blap.event.EventAccessMethod
import com.example.blap.event.EventRole
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.blap.location.LocationFix
import com.example.blap.location.LocationProvider
import com.example.blap.location.PlaceSearchRepository
import com.example.blap.location.PlaceSearchResult
import com.example.blap.venue.Venue
import com.example.blap.venue.VenueRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ApplicationViewModelTest {
    private val models = mutableListOf<ApplicationViewModel>()

    @After fun closeModels() {
        models.forEach { model -> ViewModelStore().apply { put("application", model); clear() } }
    }

    @Test fun onboardingIsStoredAndDoesNotResetOnModelRecreation() {
        val fixture = Fixture()
        val first = fixture.model()
        assertTrue(first.uiState.value.showOnboarding)
        first.completeOnboarding()
        assertFalse(first.uiState.value.showOnboarding)
        assertFalse(fixture.model().uiState.value.showOnboarding)
    }

    @Test fun nearbyPermissionDenialDoesNotStartTransport() {
        val f = Fixture()
        val model = f.model()
        model.requestNearby()
        val request = model.uiState.value.pendingRequest!!
        assertTrue(model.claimRequest(request.id))
        model.permissionResult(request.id, listOf("bluetooth"))
        assertEquals(0, f.nearbyStarts)
        assertEquals(listOf("bluetooth"), model.uiState.value.deniedNearby)
        assertNull(model.uiState.value.pendingRequest)
        model.onResume(PermissionSnapshot())
        assertTrue(model.uiState.value.deniedNearby.isEmpty())
    }

    @Test fun launchClaimSurvivesAnotherCollectorAndRejectsDuplicateDialogs() {
        val model = Fixture().model()
        model.requestContacts()
        val request = model.uiState.value.pendingRequest!!
        assertTrue(model.claimRequest(request.id))
        assertFalse(model.claimRequest(request.id))
        assertTrue(model.uiState.value.pendingRequest!!.launched)
        model.retryRequest(request.id)
        assertTrue(model.claimRequest(request.id))
    }

    @Test fun eventGpsRequiresNearbyThenLocationBeforeCheckingIn() {
        val f = Fixture()
        val model = f.model()
        model.requestEventGps()
        val nearby = model.uiState.value.pendingRequest!!
        model.permissionResult(nearby.id, emptyList())
        assertEquals(1, f.nearbyStarts)
        val location = model.uiState.value.pendingRequest!!
        assertEquals(PlatformAction.LOCATION_PERMISSION, location.action)
        assertNotEquals(nearby.id, location.id)
        assertNull(f.enteredFix)
        model.permissionResult(location.id, emptyList())
        assertEquals(f.fix, f.enteredFix)
        assertFalse(model.uiState.value.working)
    }

    @Test fun deniedEventLocationDoesNotCheckIn() {
        val f = Fixture()
        val model = f.model()
        model.requestEventGps()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        model.permissionResult(model.uiState.value.pendingRequest!!.id, listOf("location"))
        assertNull(f.enteredFix)
        assertTrue(f.errors.single().startsWith("Allow location"))
    }

    @Test fun openingAnEventStartsGpsCheckInWithoutOpeningOnSiteChat() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        model.openEvent("event-1")
        assertEquals(AppOperation.EVENT_GPS, model.uiState.value.pendingRequest!!.operation)
        assertEquals(EventPage.DETAIL, f.events!!.value.page)
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertEquals(f.fix, f.enteredFix)
        assertEquals(EventPage.DETAIL, f.events!!.value.page)
    }

    @Test fun reopeningACheckedInEventRestoresNearbyWithoutAnotherGpsRequest() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(f.eventDetail(checkedIn = true))
        f.checkedIn = true
        val model = f.model()
        model.openEvent("event-1")
        assertEquals(AppOperation.NEARBY, model.uiState.value.pendingRequest!!.operation)
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertNull(model.uiState.value.pendingRequest)
        assertNull(f.enteredFix)
    }

    @Test fun eventUpdatesDoNotRepeatADeniedAutomaticCheckIn() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        model.openEvent("event-1")
        model.permissionResult(model.uiState.value.pendingRequest!!.id, listOf("nearby"))
        f.events!!.value = f.events!!.value.copy(notice = "Refreshed")
        assertNull(model.uiState.value.pendingRequest)
        model.requestEventGps()
        assertNotNull(model.uiState.value.pendingRequest)
    }

    @Test fun aCachedCheckInLoadedAfterOpeningTheEventRestoresNearbyWithoutGps() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        f.events!!.value = f.eventDetail(checkedIn = true).copy(membership = null)
        assertNull(model.uiState.value.pendingRequest)
        f.events!!.value = f.eventDetail(checkedIn = true)
        val request = model.uiState.value.pendingRequest!!
        assertEquals(AppOperation.NEARBY, request.operation)
        model.permissionResult(request.id, emptyList())
        assertEquals(1, f.nearbyStarts)
        assertNull(model.uiState.value.pendingRequest)
        assertNull(f.enteredFix)
    }

    @Test fun eventQrUsesItsOwnScanPurpose() {
        val f = Fixture()
        val model = f.model()
        model.requestEventQr()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        val scan = model.uiState.value.pendingRequest!!
        assertEquals(PlatformAction.EVENT_QR, scan.action)
        model.qrResult(scan.id, "venue-token")
        assertEquals("venue-token", f.eventQr)
        assertNull(f.contactQr)
    }

    @Test fun membershipLoadedOnAnnouncementsStartsNearbyWithoutOpeningOnSiteChat() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        val announcements = f.eventDetail(checkedIn = true).copy(page = EventPage.ANNOUNCEMENTS)
        f.events!!.value = announcements.copy(membership = null)
        assertNull(model.uiState.value.pendingRequest)
        f.events!!.value = announcements

        val request = requireNotNull(model.uiState.value.pendingRequest)
        assertEquals(AppOperation.NEARBY, request.operation)
        model.permissionResult(request.id, emptyList())
        assertEquals(1, f.nearbyStarts)
        assertEquals(EventPage.ANNOUNCEMENTS, f.events!!.value.page)
        assertNull(f.enteredFix)
    }

    @Test fun membershipLoadedOnAnnouncementsCanCompleteInitialGpsCheckIn() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        f.events!!.value = f.eventDetail(checkedIn = false).copy(page = EventPage.ANNOUNCEMENTS)

        val request = requireNotNull(model.uiState.value.pendingRequest)
        assertEquals(AppOperation.EVENT_GPS, request.operation)
        model.permissionResult(request.id, emptyList())
        model.permissionResult(requireNotNull(model.uiState.value.pendingRequest).id, emptyList())
        assertEquals(1, f.nearbyStarts)
        assertEquals(f.fix, f.enteredFix)
        assertEquals(EventPage.ANNOUNCEMENTS, f.events!!.value.page)
    }

    @Test fun restoredOnSiteChatStartsItsCheckedInEventMesh() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(f.eventDetail(checkedIn = true).copy(page = EventPage.ON_SITE_CHAT))
        val model = f.model()

        val request = requireNotNull(model.uiState.value.pendingRequest)
        assertEquals(AppOperation.NEARBY, request.operation)
        model.permissionResult(request.id, emptyList())
        assertEquals(1, f.nearbyStarts)
        assertNull(f.enteredFix)
    }

    @Test fun announcementStartupWaitsForAnotherPlatformRequestToFinish() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(EventUiState())
        val model = f.model()
        model.requestContactQr()
        val scanner = requireNotNull(model.uiState.value.pendingRequest)
        f.events!!.value = f.eventDetail(checkedIn = true).copy(page = EventPage.ANNOUNCEMENTS)
        assertEquals(scanner, model.uiState.value.pendingRequest)
        model.qrResult(scanner.id, null, cancelled = true)

        assertEquals(AppOperation.NEARBY, requireNotNull(model.uiState.value.pendingRequest).operation)
    }

    @Test fun deniedAnnouncementStartupDoesNotRepeatWhenSwitchingEventPages() {
        val f = Fixture()
        f.screen = ChatScreen.EVENTS
        f.events = MutableStateFlow(f.eventDetail(checkedIn = true).copy(page = EventPage.ANNOUNCEMENTS))
        val model = f.model()
        model.permissionResult(requireNotNull(model.uiState.value.pendingRequest).id, listOf("nearby"))
        f.events!!.value = f.events!!.value.copy(page = EventPage.ON_SITE_CHAT)
        f.events!!.value = f.events!!.value.copy(page = EventPage.ANNOUNCEMENTS)

        assertNull(model.uiState.value.pendingRequest)
        assertEquals(0, f.nearbyStarts)
        assertEquals(listOf("nearby"), model.uiState.value.deniedNearby)
    }

    @Test fun checkedInAnnouncementsDoNotStartAnEventMeshOutsideTheEventsTab() {
        val f = Fixture()
        f.screen = ChatScreen.CHATS
        f.events = MutableStateFlow(f.eventDetail(checkedIn = true).copy(page = EventPage.ANNOUNCEMENTS))
        val model = f.model()

        assertNull(model.uiState.value.pendingRequest)
        assertEquals(0, f.nearbyStarts)
    }

    @Test fun contactQrDoesNotStartNearbyOrCheckIntoAnEvent() {
        val f = Fixture()
        val model = f.model()
        model.requestContactQr()
        model.qrResult(model.uiState.value.pendingRequest!!.id, "contact-card")
        assertEquals("contact-card", f.contactQr)
        assertEquals(0, f.nearbyStarts)
        assertNull(f.eventQr)
    }

    @Test fun cancelledQrReleasesPendingStateWithoutReportingAnError() {
        val f = Fixture()
        val model = f.model()
        model.requestContactQr()
        model.qrResult(model.uiState.value.pendingRequest!!.id, null, cancelled = true)
        assertNull(model.uiState.value.pendingRequest)
        assertTrue(f.errors.isEmpty())
        model.requestContactQr()
        assertNotNull(model.uiState.value.pendingRequest)
    }

    @Test fun blankAndFailedQrReportErrors() {
        val f = Fixture()
        val model = f.model()
        model.requestContactQr()
        model.qrResult(model.uiState.value.pendingRequest!!.id, "")
        model.requestContactQr()
        model.qrResult(model.uiState.value.pendingRequest!!.id, null, "Scanner unavailable")
        assertEquals(listOf("This QR code has no readable contact data.", "Scanner unavailable"), f.errors)
    }

    @Test fun contactImportHandlesPermissionAndReadFailures() {
        val f = Fixture()
        val model = f.model()
        model.requestContacts()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, listOf("contacts"))
        assertNull(f.imported)
        model.requestContacts()
        f.contactsRead = { error("provider failed") }
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertEquals(2, f.errors.size)
        assertFalse(model.uiState.value.working)
    }

    @Test fun successfulContactImportDelegatesRecordsToContactsViewModel() {
        val f = Fixture()
        val model = f.model()
        model.requestContacts()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertEquals(listOf(DeviceContact("Alice", "+61412345678")), f.imported)
    }

    @Test fun notificationPreferencesPersistAndOnlyEnablingAlertsRequestsPermission() {
        val f = Fixture()
        val model = f.model()
        val disabled = ChatNotificationSettings(enabled = false)
        model.updateNotificationSettings(disabled)
        assertEquals(disabled, f.notifications.value)
        assertNull(model.uiState.value.pendingRequest)
        model.updateNotificationSettings(disabled.copy(enabled = true))
        val request = model.uiState.value.pendingRequest!!
        assertEquals(PlatformAction.NOTIFICATION_PERMISSION, request.action)
        model.permissionResult(request.id, listOf("notifications"))
        assertFalse(model.uiState.value.permissions.notifications)
        assertTrue(f.notifications.value.enabled)
        model.updateNotificationSettings(f.notifications.value.copy(showPreview = false))
        assertNull(model.uiState.value.pendingRequest)
    }

    @Test fun microphoneResultUpdatesPlatformStateAndExplainsDenial() {
        val f = Fixture()
        val model = f.model()
        model.requestMicrophone()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, listOf("microphone"))
        assertFalse(model.uiState.value.permissions.microphone)
        assertEquals(1, f.errors.size)
        model.requestMicrophone()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertTrue(model.uiState.value.permissions.microphone)
    }

    @Test fun venueLookupRunsAfterAuthAndPermissionAndReportsTheResult() {
        val f = Fixture()
        val model = f.model()
        model.requestVenue()
        assertTrue(model.uiState.value.working)
        f.authCallbacks.single()(true)
        val request = model.uiState.value.pendingRequest!!
        assertEquals(PlatformAction.LOCATION_PERMISSION, request.action)
        model.permissionResult(request.id, emptyList())
        assertEquals("Nearby: Library", f.venueMessages.last())
        assertFalse(model.uiState.value.working)
    }

    @Test fun cancelledAuthCallbackCannotResumeANewerVenueRequest() {
        val f = Fixture()
        val model = f.model()
        model.requestVenue()
        model.handleBack()
        model.requestVenue()
        f.authCallbacks[0](true)
        assertNull(model.uiState.value.pendingRequest)
        assertTrue(model.uiState.value.working)
        f.authCallbacks[1](true)
        assertNotNull(model.uiState.value.pendingRequest)
    }

    @Test fun backFromEventsListReturnsToMessagesAndNestedEventBackStaysInEvents() {
        val f = Fixture()
        val model = f.model()
        f.screen = ChatScreen.EVENTS
        model.handleBack()
        assertEquals(1, f.showChatsCalls)
        f.page = EventPage.DETAIL
        model.handleBack()
        assertEquals(1, f.eventBackCalls)
        f.screen = ChatScreen.EDITING_PROFILE
        model.handleBack()
        assertEquals(1, f.chatBackCalls)
    }

    @Test fun backWhileAPermissionDialogIsOpenDiscardsItsResultBeforeAcceptingNewWork() {
        val f = Fixture()
        val model = f.model()
        model.requestEventGps()
        val old = model.uiState.value.pendingRequest!!
        model.claimRequest(old.id)
        model.handleBack()
        model.requestContacts()
        assertEquals(old.id, model.uiState.value.pendingRequest!!.id)
        model.permissionResult(old.id, emptyList())
        assertEquals(0, f.nearbyStarts)
        assertNull(model.uiState.value.pendingRequest)
        model.requestContacts()
        val next = model.uiState.value.pendingRequest!!
        model.permissionResult(old.id, emptyList())
        assertEquals(next.id, model.uiState.value.pendingRequest!!.id)
    }

    @Test fun delayedGpsResultCannotCheckIntoADifferentEvent() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<LocationFix?>()
        f.locationRead = { gate.await() }
        val model = f.model()
        model.requestEventGps()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        assertTrue(model.uiState.value.working)
        f.eventId = "new-event"
        gate.complete(f.fix)
        assertNull(f.enteredFix)
        assertFalse(model.uiState.value.working)
    }

    @Test fun accountSwitchCancelsWorkAndRejectsLatePlatformResults() = runBlocking {
        val f = Fixture()
        val gate = CompletableDeferred<LocationFix?>()
        f.locationRead = { gate.await() }
        val model = f.model()
        model.requestEventGps()
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        model.permissionResult(model.uiState.value.pendingRequest!!.id, emptyList())
        model.freezeForAccountChange()
        gate.complete(f.fix)
        assertNull(f.enteredFix)
        assertTrue(model.uiState.value.switchingAccount)
        assertFalse(model.uiState.value.working)
        model.requestGoogle()
        assertNull(model.uiState.value.pendingRequest)
        model.googleResult(1, token = "ignored-test-token")
        assertNull(f.googleToken)
    }

    @Test fun googleChooserCancellationAndSuccessfulTokenAreSeparateResults() {
        val f = Fixture()
        val model = f.model()
        model.requestGoogle()
        model.googleResult(model.uiState.value.pendingRequest!!.id, cancelled = true)
        assertEquals(listOf("Google sign-in cancelled."), f.notices)
        model.requestGoogle()
        model.googleResult(model.uiState.value.pendingRequest!!.id, token = "test-token")
        assertEquals("test-token", f.googleToken)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun unsupportedFactoryTypeDoesNotAllocateResources() {
        var created = false
        val factory = ApplicationViewModelFactory { created = true; error("Unexpected allocation") }
        assertThrows(IllegalArgumentException::class.java) { factory.create(UnsupportedViewModel::class.java) }
        assertFalse(created)
    }

    private class UnsupportedViewModel : androidx.lifecycle.ViewModel()

    private inner class Fixture {
        val notifications = FakeNotifications()
        var seen = false
        var nearbyStarts = 0
        var enteredFix: LocationFix? = null
        var eventQr: String? = null
        var contactQr: String? = null
        var imported: List<DeviceContact>? = null
        var googleToken: String? = null
        var screen = ChatScreen.CHATS
        var page = EventPage.LIST
        var eventId: String? = "event-1"
        var events: MutableStateFlow<EventUiState>? = null
        var checkedIn = false
        var showChatsCalls = 0
        var chatBackCalls = 0
        var eventBackCalls = 0
        val errors = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val venueMessages = mutableListOf<String>()
        val authCallbacks = mutableListOf<(Boolean) -> Unit>()
        val fix = LocationFix(-37.8, 144.9, 5.0, 1000)
        var locationRead: suspend () -> LocationFix? = { fix }
        var contactsRead: suspend () -> List<DeviceContact> = { listOf(DeviceContact("Alice", "+61412345678")) }
        var venueRead: suspend () -> Venue? = { Venue("library", "Library", -37.8, 144.9, 100.0) }

        fun eventDetail(checkedIn: Boolean): EventUiState {
            val now = System.currentTimeMillis()
            val event = CommunityEvent("event-1", "Meetup", "", latitude = -37.8, longitude = 144.9,
                startsAt = now - 60_000L, endsAt = now + 60_000L, createdBy = "alice")
            val membership = EventMembership(event.id, "alice", "Alice", EventRole.PRIMARY_ADMIN, now - 60_000L,
                accessMethod = if (checkedIn) EventAccessMethod.GPS else null, checkedInAt = if (checkedIn) now - 1000L else null)
            return EventUiState(page = EventPage.DETAIL, events = listOf(event), selectedEventId = event.id,
                membership = membership, activeEventId = event.id.takeIf { checkedIn })
        }

        fun model(): ApplicationViewModel = ApplicationViewModel(
            ApplicationServices(notifications, object : OnboardingStore {
                override fun hasSeenOnboarding() = seen
                override fun markSeen() { seen = true }
            }, DeviceContactsSource { contactsRead() }, object : LocationProvider {
                override suspend fun getFreshLocation() = locationRead()
            }, object : PlaceSearchRepository {
                override suspend fun search(query: String) = emptyList<PlaceSearchResult>()
            }, object : VenueRepository {
                override suspend fun findNearbyVenue() = venueRead()
            }),
            ApplicationCommands(
                startNearby = { nearbyStarts++ }, importContacts = { imported = it },
                enterEventWithGps = { enteredFix = it }, enterEventWithQr = { eventQr = it },
                importContactQr = { contactQr = it },
                ensureSignedIn = { authCallbacks.add(it) }, refreshAccount = {},
                signInWithGoogleToken = { googleToken = it }, showError = { errors.add(it) },
                showNotice = { notices.add(it) }, venueStatus = { text, _ -> venueMessages.add(text) },
                chatScreen = { screen }, eventPage = { page }, selectedEventId = { eventId },
                chatBack = { chatBackCalls++ }, eventBack = { eventBackCalls++ }, showChats = { showChatsCalls++ },
                openEvent = { eventId = it; events?.value = eventDetail(checkedIn) }, eventState = events,
            ), Dispatchers.Unconfined,
        ).also(models::add)
    }

    private class FakeNotifications : NotificationSettingsRepository {
        var value = ChatNotificationSettings()
        override fun load() = value
        override fun save(value: ChatNotificationSettings) { this.value = value }
    }
}
