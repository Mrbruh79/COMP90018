package com.example.blap.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.example.blap.application.ApplicationViewModels
import com.example.blap.application.ApplicationServices
import com.example.blap.application.DeviceContactsSource
import com.example.blap.application.OnboardingStore
import com.example.blap.di.AppContainer
import com.example.blap.location.LocationProvider
import com.example.blap.location.PlaceSearchRepository
import com.example.blap.venue.VenueRepository
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.AuthRepository
import com.example.blap.auth.AccountProfileRepository
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatViewModelTest {
    private companion object { const val TEST_GROUP = "private-test-group" }

    private fun prepareTestGroup(store: FakeChatStore) {
        store.saveGroup(PrivateGroup(TEST_GROUP, "Test friends", "local-peer", 0L,
            listOf(GroupMember("local-peer", "Alice"), GroupMember("bob", "Bob"),
                GroupMember("bob-id", "Bob"), GroupMember("carol-id", "Carol"))))
    }

    @Test fun anOutgoingCloudEchoKeepsTheOtherPersonsNameAndLocalIdentitySeparate() {
        val store = FakeChatStore().apply {
            savePeer("account:bob-uid", "Bob")
            savePeer("local-peer", "Local identity")
            saveMessage(ChatMessage("local-history", "local-peer", "Keep separate", MessageAuthor.ME, 1L,
                MessageStatus.SENT))
        }
        val vm = makeViewModel(FakeNearbyChatController(), store)
        vm.accountChanged("alice-uid")
        vm.onDirectMessage("bob-uid", CloudChatMessage("own-echo", "alice-uid", "local-peer", "Alice", "Hi", 2L))
        assertEquals("Bob", store.getConversations().single { it.peerId == "account:bob-uid" }.name)
        assertEquals(listOf("local-history"), store.getMessages("local-peer").map { it.id })
        assertEquals(listOf("own-echo"), store.getMessages("account:bob-uid").map { it.id })
    }

    @Test fun anOutgoingCloudEchoDoesNotInventASelfNamedContact() {
        val vm = makeViewModel(FakeNearbyChatController())
        vm.accountChanged("alice-uid")
        vm.onDirectMessage("bob-uid", CloudChatMessage("own-echo", "alice-uid", "local-peer", "Alice", "Hi", 2L))
        assertEquals("Online contact", vm.uiState.value.conversations.single().name)
    }

    @Test fun verifiedNearbyIdentityMatchesSavedAccountBeforeAStaleUsernameOrPeerAlias() {
        val store = FakeChatStore().apply {
            saveContact(SavedContact("bob", "Bob saved", "", "", linkedPeerId = "old-bob-peer",
                cloudUserId = "bob-uid", username = "old_bob"))
            saveContact(SavedContact("carol", "Carol saved", "", "", linkedPeerId = "bob-peer",
                cloudUserId = "carol-uid", username = "carol"))
            savePeer("account:carol-uid", "Carol saved")
            savePeer("old-bob-peer", "Bob nearby")
            saveMessage(ChatMessage("nearby-history", "old-bob-peer", "Old", MessageAuthor.PEER, 1L,
                MessageStatus.DELIVERED))
        }
        val vm = makeViewModel(FakeNearbyChatController(), store)
        vm.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob", username = "bob", accountUid = "bob-uid",
            publicKey = "verified-key", accountVerified = true))
        assertEquals("carol-uid", store.getSavedContacts().single { it.id == "carol" }.cloudUserId)
        assertEquals("bob", store.getSavedContacts().single { it.id == "bob" }.username)
        assertEquals("bob-peer", store.getSavedContacts().single { it.id == "bob" }.linkedPeerId)
        assertEquals(listOf("nearby-history"), store.getMessages("account:bob-uid").map { it.id })
        assertFalse(vm.uiState.value.conversations.single { it.peerId == "account:carol-uid" }.connected)
    }

    @Test fun aCloudMessageCannotMergeAnotherSavedAccountsNearbyHistory() {
        val store = FakeChatStore().apply {
            saveContact(SavedContact("carol", "Carol", "", "", linkedPeerId = "carol-peer", cloudUserId = "carol-uid"))
            savePeer("carol-peer", "Carol")
            saveMessage(ChatMessage("carol-history", "carol-peer", "Separate", MessageAuthor.PEER, 1L,
                MessageStatus.DELIVERED))
        }
        val vm = makeViewModel(FakeNearbyChatController(), store)
        vm.accountChanged("alice-uid")
        vm.onDirectMessage("bob-uid", CloudChatMessage("bob-message", "bob-uid", "carol-peer", "Bob", "Hi", 2L))
        assertEquals(listOf("carol-history"), store.getMessages("account:carol-uid").map { it.id })
        assertEquals(listOf("bob-message"), store.getMessages("account:bob-uid").map { it.id })
    }

    @Test fun failingDeviceKeyPublicationDoesNotReportAccountLookupAsFailed() {
        val cloud = FakeCloudChatController().apply { failKeyPublication = true }
        val identity = SnapshotIdentityStore(ContactProfile("Alice", username = "alice_user"), 0)
        val vm = MessagingTestHarness(FakeNearbyChatController(), FakeChatStore(), identity,
            cloudChatController = cloud, initialAccountId = "alice-uid")
        vm.applyAccountProfile("alice_user", "Alice")
        assertEquals("Phone lookup is off", vm.uiState.value.onlineLookupStatus)
        assertTrue(vm.uiState.value.notice.orEmpty().contains("Nearby identity"))
    }

    @Test fun startingNearbyRetriesDeviceKeyPublicationWithoutWaitingForIt() {
        val cloud = FakeCloudChatController()
        val identity = SnapshotIdentityStore(ContactProfile("Alice", username = "alice_user"), 0)
        val vm = MessagingTestHarness(FakeNearbyChatController(), FakeChatStore(), identity,
            cloudChatController = cloud, initialAccountId = "alice-uid")
        vm.startChat()
        assertTrue(vm.uiState.value.nearbyActive)
        assertEquals(1, cloud.keyPublications)
    }

    @Test
    fun unknownNearbyRequestsNeedApprovalWithoutLeavingTheCurrentScreen() {
        val controller = FakeNearbyChatController()
        val vm = makeViewModel(controller)
        vm.updateDisplayName("Alice")
        vm.startChat()
        vm.beginAddContact()
        vm.updateContactName("Draft")
        vm.onConnectionInitiated(NearbyDevice("bob-endpoint", "Bob"), "1234")
        assertEquals(ChatScreen.EDITING_CONTACT, vm.uiState.value.screen)
        assertEquals("Draft", vm.uiState.value.contactNameDraft)
        assertEquals("1234", vm.uiState.value.connectionRequests.single().authenticationDigits)
        assertTrue(controller.accepted.isEmpty())
        vm.chat.acceptConnection("bob-endpoint")
        assertEquals(listOf("bob-endpoint"), controller.accepted)
        assertTrue(vm.uiState.value.connectionRequests.isEmpty())
    }

    @Test
    fun rememberedDevicesReconnectOfflineWithoutAPrompt() {
        val controller = FakeNearbyChatController().apply { identityProofAvailable = true }
        val vm = makeViewModel(controller)
        vm.updateDisplayName("Alice")
        vm.startChat()
        val trusted = TrustedNearbyIdentity("bob-peer", "bob", "bob-uid", "remembered-key")
        vm.session.dependencies.nearbyIdentities.remember(trusted)
        vm.onConnectionInitiated(NearbyDevice("new-endpoint", "Bob", "bob-peer", "bob"), "1234")
        assertEquals(listOf("new-endpoint" to trusted), controller.knownAccepted)
        assertTrue(vm.uiState.value.connectionRequests.isEmpty())
    }

    @Test
    fun savedOnlineAccountsCanConnectUsingTheirPublishedKeyWithoutAPrompt() {
        val controller = FakeNearbyChatController().apply { identityProofAvailable = true }
        val store = FakeChatStore().apply {
            saveContact(SavedContact("bob-contact", "Bob", "", "", username = "bob", cloudUserId = "bob-uid"))
            saveContact(SavedContact("old-bob-duplicate", "Bob", "", "", username = "bob", cloudUserId = "bob-uid"))
        }
        val cloud = FakeCloudChatController().apply {
            addAccount("bob-uid", "Bob", "bob-peer", username = "bob", nearbyPublicKey = "published-key")
        }
        val vm = makeViewModel(controller, store, cloud = cloud)
        vm.updateDisplayName("Alice")
        vm.accountChanged("alice-uid")
        vm.startChat()
        vm.onConnectionInitiated(NearbyDevice("new-endpoint", "Bob", "bob-peer", "bob"), "1234")
        assertEquals("published-key", controller.knownAccepted.single().second.publicKey)
        assertTrue(vm.uiState.value.connectionRequests.isEmpty())
    }

    @Test
    fun nearbyAndOnlineEntriesForTheSameUsernameMergeIntoOneContactAndChat() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore().apply {
            saveContact(SavedContact("online-bob", "Bob", "+12025550198", requireNotNull(PhoneIdentity.hash("+12025550198")),
                cloudUserId = "bob-uid", username = "bob", email = "bob@example.com"))
            saveContact(SavedContact("nearby-bob", "Bob nearby", "", "", linkedPeerId = "bob-peer", bio = "From the shared card"))
            saveContact(SavedContact("duplicate-online-bob", "Bob", "", "", cloudUserId = "bob-uid", username = "bob"))
            savePeer("account:bob-uid", "Bob")
            savePeer("bob-peer", "Bob nearby")
            saveMessage(ChatMessage("old-dm", "bob-peer", "Earlier Nearby message", MessageAuthor.PEER, 1L,
                MessageStatus.DELIVERED, senderId = "bob-peer"))
        }
        val vm = makeViewModel(controller, store)
        vm.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob", username = "bob", accountUid = "bob-uid",
            publicKey = "verified-key", accountVerified = true))
        assertEquals(1, store.getSavedContacts().size)
        assertEquals("bob", store.getSavedContacts().single().username)
        assertEquals("bob-peer", store.getSavedContacts().single().linkedPeerId)
        assertEquals("+12025550198", store.getSavedContacts().single().phoneNumber)
        assertEquals("bob@example.com", store.getSavedContacts().single().email)
        assertEquals("From the shared card", store.getSavedContacts().single().bio)
        assertEquals(listOf("account:bob-uid"), vm.uiState.value.conversations.map { it.peerId })
        assertEquals("old-dm", store.getMessages("account:bob-uid").single().id)
        vm.openConversation("account:bob-uid")
        vm.sendMessage("Nearby reply")
        assertEquals("bob-peer", controller.sentMessages.single().peerId)
    }

    @Test
    fun aUsernameClaimWithoutVerifiedAccountBindingDoesNotMergeIntoAnOnlineChat() {
        val store = FakeChatStore().apply {
            saveContact(SavedContact("bob-contact", "Bob", "", "", cloudUserId = "bob-uid", username = "bob"))
            savePeer("account:bob-uid", "Bob")
        }
        val vm = makeViewModel(FakeNearbyChatController(), store)
        vm.onConnected(ConnectedPeer("fake-peer", "endpoint", "Bob", username = "bob", accountUid = "bob-uid",
            publicKey = "unverified-key"))
        assertNull(store.getSavedContacts().single().linkedPeerId)
        assertEquals(2, vm.uiState.value.conversations.size)
    }

    @Test fun messagesReceivedWhileTheAccountIsBeingMatchedStayInOneChat() = runBlocking {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val matched = CompletableDeferred<Unit>()
        cloud.beforeLookup = { matched.await() }
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob", nearbyPublicKey = "verified-key")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob", username = "bob", accountUid = "bob-uid",
            publicKey = "verified-key"))
        vm.onMessageReceived(IncomingNearbyMessage("before-match", "bob-peer", "bob-peer", "Bob", "", "First", 1L))
        matched.complete(Unit)
        vm.openConversation("account:bob-uid")
        vm.onMessageReceived(IncomingNearbyMessage("after-match", "bob-peer", "bob-peer", "Bob", "", "Second", 2L))
        assertEquals(listOf("account:bob-uid"), store.getConversations().map { it.peerId })
        assertEquals(setOf("before-match", "after-match"), vm.uiState.value.messages.map { it.id }.toSet())
        vm.onDisconnected("bob-peer")
        assertFalse(vm.uiState.value.conversations.single().connected)
    }

    @Test fun anApprovedNearbyChatCanOpenAndSendWhileCloudIdentityLookupIsWaiting() {
        val nearby = FakeNearbyChatController()
        val cloud = FakeCloudChatController()
        val gate = CompletableDeferred<Unit>()
        cloud.beforeLookup = { gate.await() }
        val vm = makeViewModel(nearby, cloud = cloud)
        vm.updateDisplayName("Alice")
        vm.accountChanged("alice-uid")
        vm.startChat()
        vm.onDeviceFound(NearbyDevice("bob-endpoint", "Bob", "bob-peer", "bob"))
        vm.connectToDevice("bob-endpoint")
        vm.onConnected(ConnectedPeer("bob-peer", "bob-endpoint", "Bob", username = "bob", accountUid = "bob-uid",
            publicKey = "approved-device-key"))
        assertEquals(ChatScreen.CONVERSATION, vm.uiState.value.screen)
        assertEquals(1, vm.uiState.value.directConnectionCount)
        vm.sendMessage("Works without waiting for the cloud")
        assertEquals("bob-peer", nearby.sentMessages.single().peerId)
    }

    @Test
    fun decliningOneRequestKeepsOtherRequestsAndStoppingClearsThem() {
        val controller = FakeNearbyChatController()
        val vm = makeViewModel(controller)
        vm.updateDisplayName("Alice")
        vm.startChat()
        vm.onConnectionInitiated(NearbyDevice("bob-endpoint", "Bob"), "1234")
        vm.onConnectionInitiated(NearbyDevice("carol-endpoint", "Carol"), "5678")
        vm.chat.rejectConnection("bob-endpoint")
        assertEquals(listOf("bob-endpoint"), controller.rejected)
        assertEquals("carol-endpoint", vm.uiState.value.connectionRequests.single().device.endpointId)
        vm.stopChat()
        assertTrue(vm.uiState.value.connectionRequests.isEmpty())
        vm.chat.acceptConnection("carol-endpoint")
        assertTrue(controller.accepted.isEmpty())
    }

    @Test
    fun retiredPublicChatIsHiddenAndIncomingPublicMessagesAreDropped() {
        val store = FakeChatStore().apply { savePeer(MeshGroup.ID, "Legacy public room") }
        val controller = FakeNearbyChatController()
        val vm = makeViewModel(controller, store)
        assertTrue(vm.uiState.value.conversations.isEmpty())
        vm.openConversation(MeshGroup.ID)
        assertNull(vm.uiState.value.selectedPeerId)
        vm.onMessageReceived(IncomingNearbyMessage("old", MeshGroup.ID, "bob", "Bob", "", "public", 1L))
        assertTrue(store.getMessages(MeshGroup.ID).isEmpty())
        assertTrue(controller.acknowledgements.isEmpty())
    }
    @Test
    fun activityGraphReusesModelsWithoutCreatingAnotherAccountSession() {
        val scopes = mutableListOf<String>()
        val authentication = FakeAuthRepository(AuthAccount(uid = "alice"))
        val container = integrationContainer(authentication, scopes)
        val retained = ViewModelStore()
        val firstOwner = object : ViewModelStoreOwner { override val viewModelStore = retained }
        val secondOwner = object : ViewModelStoreOwner { override val viewModelStore = retained }
        val first = ApplicationViewModels.obtain(firstOwner, container)
        first.application.requestEventQr()
        val second = ApplicationViewModels.obtain(secondOwner, container)
        assertSame(first.application, second.application)
        assertSame(first.chat, second.chat)
        assertSame(first.auth, second.auth)
        assertSame(first.events, second.events)
        assertEquals(first.application.uiState.value.pendingRequest, second.application.uiState.value.pendingRequest)
        assertEquals(listOf("alice"), scopes)
        retained.clear()
    }

    @Test
    fun accountRestartCreatesANewGraphWithTheNewAccountsPreferences() {
        val scopes = mutableListOf<String>()
        val authentication = FakeAuthRepository(AuthAccount(uid = "alice"))
        val container = integrationContainer(authentication, scopes)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val alice = ApplicationViewModels.obtain(owner, container)
        alice.application.updateNotificationSettings(ChatNotificationSettings(enabled = false))
        alice.auth.signOut()
        assertTrue(alice.application.uiState.value.switchingAccount)
        owner.viewModelStore.clear()
        authentication.account = AuthAccount(uid = "bob")
        val bob = ApplicationViewModels.obtain(owner, container)
        assertNotSame(alice.application, bob.application)
        assertEquals("bob", bob.chat.uiState.value.onlineAccountId)
        assertTrue(bob.application.uiState.value.notifications.enabled)
        assertEquals(listOf("alice", "bob"), scopes)
        owner.viewModelStore.clear()
    }

    private fun integrationContainer(auth: AuthRepository, scopes: MutableList<String>): AppContainer = object : AppContainer {
        override val authentication = auth
        override val locationProvider = object : LocationProvider {
            override suspend fun getFreshLocation() = null
        }
        override val placeSearch = object : PlaceSearchRepository {
            override suspend fun search(query: String) = emptyList<com.example.blap.location.PlaceSearchResult>()
        }
        override val venues = object : VenueRepository {
            override suspend fun findNearbyVenue() = null
        }
        override val privateProfiles = RecordingPrivateProfileStore()
        private val preferences = mutableMapOf<String, NotificationSettingsRepository>()
        override fun identityFor(accountId: String): IdentityStore = FakeIdentityStore()
        override fun notificationSettingsFor(accountId: String) = preferences.getOrPut(accountId) {
            object : NotificationSettingsRepository {
                var value = ChatNotificationSettings()
                override fun load() = value
                override fun save(value: ChatNotificationSettings) { this.value = value }
            }
        }
        override fun chatViewModelFactory(accountId: String): ViewModelProvider.Factory = error("Use session owner")
        override fun messagingSessionFactory(accountId: String): ViewModelProvider.Factory = MessagingSessionFactory {
            scopes.add(accountId)
            ChatDependencies(FakeNearbyChatController(), FakeChatStore(), identityFor(accountId), accountId = accountId,
                authRepository = auth, accountProfileRepository = FakeAccountProfiles(), ioDispatcher = Dispatchers.Unconfined)
        }
        override fun applicationServicesFor(accountId: String) = ApplicationServices(
            notificationSettingsFor(accountId), object : OnboardingStore {
                override fun hasSeenOnboarding() = true
                override fun markSeen() = Unit
            }, DeviceContactsSource { emptyList() }, locationProvider, placeSearch, venues,
        )
    }

    @Test
    fun delayedProfileLoadCannotRestoreTheSignedOutAccount() = runBlocking {
        val pending = CompletableDeferred<PublicAccountProfile?>()
        val authentication = FakeAuthRepository(AuthAccount(uid = "alice"))
        val profiles = object : AccountProfileRepository {
            override fun validate(username: String, displayName: String): String? = null
            override suspend fun load() = pending.await()
            override suspend fun claim(username: String, displayName: String) = PublicAccountProfile(username, displayName)
        }
        val identity = FakeIdentityStore()
        val session = MessagingSession(ChatDependencies(FakeNearbyChatController(), FakeChatStore(), identity,
            accountId = "alice", authRepository = authentication, accountProfileRepository = profiles,
            ioDispatcher = Dispatchers.Unconfined)).also(testSessions::add)
        val owner = ViewModelStore()
        val auth = AuthViewModel(session).also { owner.put("auth", it) }
        auth.initialize()
        assertTrue(auth.uiState.value.profileLoading)
        auth.signOut()
        pending.complete(PublicAccountProfile("alice", "Alice"))
        assertNull(auth.uiState.value.profile)
        assertEquals("", auth.uiState.value.account.uid)
        assertEquals("", session.uiState.value.displayName)
        assertTrue(auth.uiState.value.accountChange!!.clearCredentials)
        owner.clear()
    }

    @Test
    fun delayedSignInCallbackCannotReplaceTheSignOutRestart() {
        var completeSignIn: (() -> Unit)? = null
        val base = FakeAuthRepository(AuthAccount(uid = "alice"))
        val authentication = object : AuthRepository by base {
            override fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
                completeSignIn = onSuccess
            }
        }
        val session = MessagingSession(ChatDependencies(FakeNearbyChatController(), FakeChatStore(), FakeIdentityStore(),
            accountId = "alice", authRepository = authentication, accountProfileRepository = FakeAccountProfiles(),
            ioDispatcher = Dispatchers.Unconfined)).also(testSessions::add)
        val owner = ViewModelStore()
        val auth = AuthViewModel(session).also { owner.put("auth", it) }
        auth.signInWithEmail("alice@example.com", "password")
        auth.signOut()
        completeSignIn!!.invoke()
        assertTrue(auth.uiState.value.accountChange!!.clearCredentials)
        owner.clear()
    }

    @Test
    fun externallyChangedAccountClosesTheOldScopeBeforeRequestingRecreation() {
        val authentication = FakeAuthRepository(AuthAccount(uid = "alice", emailVerified = true))
        val store = FakeChatStore()
        val session = MessagingSession(ChatDependencies(FakeNearbyChatController(), store, FakeIdentityStore(),
            accountId = "alice", authRepository = authentication, accountProfileRepository = FakeAccountProfiles(),
            ioDispatcher = Dispatchers.Unconfined)).also(testSessions::add)
        val owner = ViewModelStore()
        val auth = AuthViewModel(session).also { owner.put("auth", it) }
        authentication.account = AuthAccount(uid = "bob")
        auth.refreshAccount()
        assertTrue(store.closed)
        assertNotNull(auth.uiState.value.accountChange)
        assertEquals("alice", session.uiState.value.onlineAccountId)
        owner.clear()
    }

    @Test
    fun splitFeatureModelsShareOneSessionAndDoNotCloseSiblingResources() {
        val nearby = FakeNearbyChatController()
        val store = FakeChatStore()
        val session = MessagingSession(ChatDependencies(nearby, store, FakeIdentityStore(),
            ioDispatcher = Dispatchers.Unconfined)).also(testSessions::add)
        val factory = MessagingViewModelFactory(session)
        val features = ViewModelStore()
        val owner = ViewModelStore()
        owner.put("session", MessagingSessionOwner(session))
        val chat = factory.create(ChatViewModel::class.java).also { features.put("chat", it) }
        val contacts = factory.create(ContactsViewModel::class.java).also { features.put("contacts", it) }
        val groups = factory.create(GroupsViewModel::class.java).also { features.put("groups", it) }
        val profile = factory.create(ProfileViewModel::class.java).also { features.put("profile", it) }
        assertSame(chat.uiState, contacts.uiState)
        assertSame(chat.uiState, groups.uiState)
        assertSame(chat.uiState, profile.uiState)
        profile.updateDisplayName("Alice")
        assertEquals("Alice", chat.uiState.value.displayName)

        features.clear()
        assertFalse(store.closed)
        assertFalse(nearby.closed)
        owner.clear()
        session.close()
        assertEquals(1, store.closeCalls)
        assertEquals(1, nearby.closeCalls)
    }

    @Test
    fun signOutHidesChatsAndRetainsRestartUntilTheActivityHandlesIt() = runBlocking {
        val nearby = FakeNearbyChatController()
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val authentication = FakeAuthRepository(AuthAccount(uid = "alice"))
        val session = MessagingSession(ChatDependencies(nearby, store, FakeIdentityStore(), accountId = "alice",
            cloudChatController = cloud, authRepository = authentication,
            accountProfileRepository = FakeAccountProfiles(), ioDispatcher = Dispatchers.Unconfined))
            .also(testSessions::add)
        val features = ViewModelStore()
        val chat = ChatViewModel(session).also { features.put("chat", it) }
        val auth = AuthViewModel(session).also { features.put("auth", it) }
        store.savePeer(TEST_GROUP, "Test chat")
        session.persistence.reloadConversationsNow()
        chat.openConversation(TEST_GROUP)
        chat.sendMessage("A local message")
        assertEquals(1, session.uiState.value.messages.size)

        auth.signOut()

        assertTrue(session.uiState.value.messages.isEmpty())
        assertTrue(session.uiState.value.savedContacts.isEmpty())
        assertEquals("", auth.uiState.value.account.uid)
        assertTrue(auth.accountChanges.first().clearCredentials)
        assertTrue(auth.accountChanges.first().clearCredentials)
        assertTrue(store.closed)
        assertTrue(nearby.closed)
        assertNull(cloud.listener)
        features.clear()
    }

    @Test
    fun signupWritesTheNewAccountIdentityWithoutChangingTheOldScope() = runBlocking {
        val oldIdentity = SnapshotIdentityStore(ContactProfile(displayName = "Guest"), 0)
        val newIdentity = SnapshotIdentityStore(ContactProfile(), 0)
        val authentication = FakeAuthRepository()
        val backup = RecordingPrivateProfileStore()
        val session = MessagingSession(ChatDependencies(FakeNearbyChatController(), FakeChatStore(), oldIdentity,
            authRepository = authentication, accountProfileRepository = FakeAccountProfiles(),
            privateProfileStore = backup, identityFor = { uid ->
                assertEquals("new-account", uid)
                newIdentity
            }, ioDispatcher = Dispatchers.Unconfined)).also(testSessions::add)
        val owner = ViewModelStore()
        val auth = AuthViewModel(session).also { owner.put("auth", it) }

        auth.registerEmail("alice@example.com", "password", "alice", "Alice")

        assertEquals("alice", newIdentity.getProfile().username)
        assertEquals("Alice", newIdentity.getProfile().displayName)
        assertEquals("Guest", oldIdentity.getProfile().displayName)
        assertEquals("", session.uiState.value.onlineAccountId)
        assertEquals("alice", backup.saved?.profile?.username)
        assertNull(auth.accountChanges.first().setupError)
        owner.clear()
    }

    @Test
    fun sessionFactoryRejectsUnknownTypesBeforeAllocatingResources() {
        var created = false
        val factory = MessagingSessionFactory { created = true; error("Not expected") }
        assertThrows(IllegalArgumentException::class.java) { factory.create(ChatViewModel::class.java) }
        assertFalse(created)
    }

    @Test
    fun factoryRejectsUnknownViewModelsWithoutCreatingResources() {
        var created = false
        val factory = ChatViewModelFactory {
            created = true
            error("Dependencies should not be constructed")
        }

        assertThrows(IllegalArgumentException::class.java) {
            factory.create(UnsupportedViewModel::class.java)
        }
        assertFalse(created)
    }

    @Test
    fun factoryWiresAccountIdentityStoresAndTransportListeners() {
        val nearby = FakeNearbyChatController()
        val store = FakeChatStore()
        val identity = SnapshotIdentityStore(ContactProfile(displayName = "Alice", username = "alice"), 10)
        val cloud = FakeCloudChatController()
        val factory = ChatViewModelFactory {
            ChatDependencies(nearby, store, identity, accountId = "alice-account",
                cloudChatController = cloud, ioDispatcher = Dispatchers.Unconfined)
        }
        val owner = ViewModelStore()

        try {
            val viewModel = factory.create(ChatViewModel::class.java)
            owner.put("chat", viewModel)
            assertEquals("alice-account", viewModel.uiState.value.onlineAccountId)
            assertEquals("Alice", viewModel.uiState.value.displayName)
            assertEquals(identity.getPeerId(), viewModel.uiState.value.myPeerId)
            assertEquals("alice-account", cloud.startedAccountId)
            assertSame(viewModel, nearby.listener)
            assertTrue(cloud.listener is CloudChatSynchronizer)
            assertTrue(store.getConversations().isEmpty())
        } finally {
            owner.clear()
        }
    }

    @Test
    fun factoryCreatesFreshResourcesForEachViewModel() {
        val transports = mutableListOf<FakeNearbyChatController>()
        val factory = ChatViewModelFactory {
            val nearby = FakeNearbyChatController().also(transports::add)
            ChatDependencies(nearby, FakeChatStore(), FakeIdentityStore(),
                ioDispatcher = Dispatchers.Unconfined)
        }
        val owner = ViewModelStore()

        try {
            assertTrue(transports.isEmpty())
            val first = factory.create(ChatViewModel::class.java)
            owner.put("first", first)
            val second = factory.create(ChatViewModel::class.java)
            owner.put("second", second)
            assertEquals(2, transports.size)
            assertNotSame(first, second)
            assertNotSame(transports[0], transports[1])
            assertSame(first, transports[0].listener)
            assertSame(second, transports[1].listener)
        } finally {
            owner.clear()
        }
        assertTrue(transports.all { it.closed })
    }

    @Test
    fun factoryResourcesAreReleasedWhenViewModelOwnerIsCleared() {
        val nearby = FakeNearbyChatController()
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val factory = ChatViewModelFactory {
            ChatDependencies(nearby, store, FakeIdentityStore(), accountId = "alice-account",
                cloudChatController = cloud, ioDispatcher = Dispatchers.Unconfined)
        }
        val owner = ViewModelStore()
        owner.put("chat", factory.create(ChatViewModel::class.java))

        owner.clear()

        assertTrue(nearby.closed)
        assertTrue(store.closed)
        assertNull(cloud.listener)
    }

    private class UnsupportedViewModel : ViewModel()

    @Test
    fun reloginRestoresCardAndOptInDiscoveryFromNewerPrivateCopy() {
        val identity = SnapshotIdentityStore(ContactProfile(displayName = "Alice", username = "alice"), 10)
        val viewModel = MessagingTestHarness(
            FakeNearbyChatController(), FakeChatStore(), identity, Dispatchers.Unconfined,
            initialAccountId = "alice",
        )
        val remote = ContactProfile(
            displayName = "Alice", username = "alice", phoneNumber = "+61412345678",
            lookupPhoneNumber = "+61499999999", discoverableByPhone = true,
            bio = "From my card", instagramUrl = "https://instagram.com/alice",
        )

        viewModel.restorePrivateProfile(PrivateProfileSnapshot(remote, 20), "alice", "Alice")

        assertEquals(remote.phoneNumber, viewModel.uiState.value.phoneNumber)
        assertEquals(remote.lookupPhoneNumber, viewModel.uiState.value.profileLookupPhoneNumber)
        assertTrue(viewModel.uiState.value.profileDiscoverableByPhone)
        assertEquals(remote.bio, identity.getProfile().bio)
        assertEquals(20L, identity.profileUpdatedAt())
    }

    @Test
    fun reloginKeepsNewerLocalProfileAndBacksItUp() {
        val local = ContactProfile(
            displayName = "Alice", username = "alice", phoneNumber = "+61412345678",
            lookupPhoneNumber = "+61412345678", discoverableByPhone = true,
        )
        val identity = SnapshotIdentityStore(local, 30)
        val backup = RecordingPrivateProfileStore()
        val viewModel = MessagingTestHarness(
            FakeNearbyChatController(), FakeChatStore(), identity, Dispatchers.Unconfined,
            initialAccountId = "alice", privateProfileStore = backup,
        )

        viewModel.restorePrivateProfile(
            PrivateProfileSnapshot(local.copy(phoneNumber = ""), 20), "alice", "Alice",
        )

        assertEquals(local.phoneNumber, viewModel.uiState.value.phoneNumber)
        assertEquals(local.lookupPhoneNumber, backup.saved?.profile?.lookupPhoneNumber)
        assertEquals(30L, backup.saved?.updatedAt)
    }

    @Test
    fun legacyCardIsNotReplacedByBlankBackup() {
        val local = ContactProfile(displayName = "Alice", username = "alice",
            phoneNumber = "+61412345678", lookupPhoneNumber = "+61412345678",
            discoverableByPhone = true)
        val identity = SnapshotIdentityStore(local, 0)
        val backup = RecordingPrivateProfileStore()
        val viewModel = MessagingTestHarness(
            FakeNearbyChatController(), FakeChatStore(), identity, Dispatchers.Unconfined,
            initialAccountId = "alice", privateProfileStore = backup,
        )

        viewModel.restorePrivateProfile(
            PrivateProfileSnapshot(ContactProfile(displayName = "Alice", username = "alice"), 20),
            "alice", "Alice",
        )

        assertEquals(local.phoneNumber, viewModel.uiState.value.phoneNumber)
        assertTrue(viewModel.uiState.value.profileDiscoverableByPhone)
        assertEquals(local.phoneNumber, backup.saved?.profile?.phoneNumber)
        assertTrue(identity.profileUpdatedAt() > 20)
    }

    private class SnapshotIdentityStore(
        private var profile: ContactProfile,
        private var changedAt: Long,
    ) : IdentityStore {
        override fun getPeerId() = "local-peer"
        override fun getDisplayName() = profile.displayName
        override fun saveDisplayName(name: String) { profile = profile.copy(displayName = name) }
        override fun getPhoneNumber() = profile.phoneNumber
        override fun savePhoneNumber(phoneNumber: String) { profile = profile.copy(phoneNumber = phoneNumber) }
        override fun getProfile() = profile
        override fun saveProfile(profile: ContactProfile) { this.profile = profile }
        override fun profileUpdatedAt() = changedAt
        override fun saveProfileAt(profile: ContactProfile, updatedAt: Long) {
            this.profile = profile
            changedAt = updatedAt
        }
    }

    private class RecordingPrivateProfileStore : PrivateProfileStore {
        var saved: PrivateProfileSnapshot? = null
        override suspend fun load(uid: String): PrivateProfileSnapshot? = null
        override suspend fun save(uid: String, snapshot: PrivateProfileSnapshot) { saved = snapshot }
    }

    @Test
    fun startChatNeedsAName() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)

        viewModel.startChat()

        val state = viewModel.uiState.value
        assertFalse(controller.advertisingStarted)
        assertEquals("Please enter a display name", state.error)
        assertNull(state.nameError)
        assertNull(state.phoneError)
    }

    @Test
    fun completeSetupReportsFieldErrors() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.updatePhoneNumber("123")

        viewModel.completeSetup()

        val state = viewModel.uiState.value
        assertEquals("Please enter a display name", state.nameError)
        assertEquals("Enter a valid phone number with your country code", state.phoneError)
        assertNull(state.error)
        assertEquals(ChatScreen.WELCOME, state.screen)
    }

    @Test
    fun editingAFieldClearsOnlyItsOwnError() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.updatePhoneNumber("123")
        viewModel.completeSetup()

        viewModel.updateDisplayName("Alice")

        assertNull(viewModel.uiState.value.nameError)
        assertNotNull(viewModel.uiState.value.phoneError)
    }

    @Test
    fun completeSetupNavigatesWhenValid() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.updateDisplayName("  Alice  ")

        viewModel.completeSetup()

        val state = viewModel.uiState.value
        assertEquals(ChatScreen.CHATS, state.screen)
        assertEquals("Alice", state.displayName)
        assertNull(state.nameError)
        assertNull(state.phoneError)
    }

    @Test
    fun nearbyProfileCanBeCreatedWithoutPhoneNumber() {
        val identity = FakeIdentityStore()
        identity.savePhoneNumber("")
        val viewModel = makeViewModel(FakeNearbyChatController(), identityStore = identity)
        viewModel.updateDisplayName("Alex")

        viewModel.completeSetup()

        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
        assertEquals("", identity.getPhoneNumber())
    }

    @Test
    fun startChatAdvertisesAndDiscovers() {
        val controller = FakeNearbyChatController()
        val identity = FakeIdentityStore()
        val viewModel = makeViewModel(controller, identityStore = identity)
        viewModel.updateDisplayName("  Alice  ")

        viewModel.startChat()

        assertEquals("Alice", controller.advertisedName)
        assertEquals(identity.expectedPeerId, controller.advertisedPeerId)
        assertTrue(controller.discoveryStarted)
        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
    }

    @Test
    fun devicesAreSortedAndRemovedByEndpoint() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)

        controller.listener?.onDeviceFound(NearbyDevice("2", "Zara"))
        controller.listener?.onDeviceFound(NearbyDevice("1", "Bob"))
        assertEquals(listOf("Bob", "Zara"), viewModel.uiState.value.discoveredDevices.map { it.name })

        controller.listener?.onDeviceLost("1")
        assertEquals(listOf("Zara"), viewModel.uiState.value.discoveredDevices.map { it.name })
    }

    @Test
    fun sentAndReceivedMessagesAreStored() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        val bob = ConnectedPeer("bob-id", "endpoint-bob", "Bob")
        controller.listener?.onConnectionInitiated(NearbyDevice("endpoint-bob", "Bob"), "1234")
        controller.listener?.onConnected(bob)
        viewModel.openConversation(bob.peerId)

        viewModel.sendMessage("  Hello Bob  ")
        val outgoing = controller.sentMessages.lastOrNull()
        assertNotNull(outgoing)
        val sentMessage = requireNotNull(outgoing)
        controller.listener?.onMessageSent("bob-id", sentMessage.messageId)
        controller.listener?.onMessageReceived(
            IncomingNearbyMessage(
                "reply-id",
                "bob-id",
                "bob-id",
                "Bob",
                "",
                "Hello Alice",
                sentMessage.sentAt + 1,
            ),
        )

        assertEquals(listOf("Hello Bob", "Hello Alice"), store.getMessages("bob-id").map { it.text })
        assertEquals(listOf(MessageAuthor.ME, MessageAuthor.PEER), store.getMessages("bob-id").map { it.author })
    }

    @Test
    fun pendingMessageSendsWhenPeerReconnects() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        store.savePeer("bob-id", "Bob")
        val viewModel = makeViewModel(controller, store)
        viewModel.openConversation("bob-id")

        viewModel.sendMessage("Are you there?")
        assertTrue(controller.sentMessages.isEmpty())
        assertEquals(MessageStatus.PENDING, store.getMessages("bob-id").single().status)

        controller.listener?.onConnected(ConnectedPeer("bob-id", "endpoint-bob", "Bob"))
        assertEquals("Are you there?", controller.sentMessages.single().text)
    }

    @Test
    fun sentMessageWithoutDeliveryReceiptRetries() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        store.savePeer("bob-id", "Bob")
        store.saveMessage(
            ChatMessage(
                id = "message-1",
                peerId = "bob-id",
                text = "Try this again",
                author = MessageAuthor.ME,
                sentAt = 100L,
                status = MessageStatus.SENT,
            ),
        )
        makeViewModel(controller, store)

        controller.listener?.onConnected(ConnectedPeer("bob-id", "endpoint-bob", "Bob"))

        assertEquals("message-1", controller.sentMessages.single().messageId)
    }

    @Test
    fun incomingMessageIsStoredBeforeItIsAcknowledged() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        makeViewModel(controller, store)
        controller.listener?.onConnected(ConnectedPeer("bob-id", "endpoint-bob", "Bob"))

        controller.listener?.onMessageReceived(
            IncomingNearbyMessage("message-2", "bob-id", "bob-id", "Bob", "", "Made it", 200L),
        )

        assertEquals("Made it", store.getMessages("bob-id").single().text)
        assertEquals("bob-id" to "message-2", controller.acknowledgements.single())
    }

    @Test
    fun groupMessagesUseTheSharedConversationAndOriginalSender() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        controller.listener?.onConnected(ConnectedPeer("bob-id", "endpoint-bob", "Bob"))
        viewModel.openConversation(TEST_GROUP)

        viewModel.sendMessage("Hello mesh")
        controller.listener?.onMessageReceived(
            IncomingNearbyMessage(
                messageId = "remote-group-message",
                conversationId = TEST_GROUP,
                senderId = "carol-id",
                senderName = "Carol",
                senderPhoneHash = "",
                text = "Reached you through Bob",
                sentAt = 300L,
            ),
        )

        assertEquals(TEST_GROUP, controller.sentMessages.single().peerId)
        val received = store.getMessages(TEST_GROUP).last { it.id == "remote-group-message" }
        assertEquals("Carol", received.senderName)
        assertEquals(MessageAuthor.PEER, received.author)
    }

    @Test
    fun connectingANeighborSynchronizesStoredGroupHistory() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        store.saveMessage(
            ChatMessage(
                id = "old-group-message",
                peerId = TEST_GROUP,
                text = "Earlier message",
                author = MessageAuthor.PEER,
                sentAt = 100L,
                status = MessageStatus.DELIVERED,
                senderId = "carol-id",
                senderName = "Carol",
                senderPhoneHash = "carol-phone-hash",
            ),
        )
        makeViewModel(controller, store)

        controller.listener?.onConnected(ConnectedPeer("bob-id", "endpoint-bob", "Bob"))

        val sync = controller.groupSynchronizations.single()
        assertEquals("bob-id", sync.first)
        assertEquals("old-group-message", sync.second.single().messageId)
        assertEquals("carol-id", sync.second.single().senderId)
    }

    @Test
    fun privateGroupCanBeCreatedFromSavedContacts() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore().apply { savePeer("bob-id", "Bob") }
        val viewModel = makeViewModel(controller, store)
        viewModel.updateDisplayName("Alice")

        viewModel.beginCreateGroup()
        viewModel.updateGroupName("Trip crew")
        viewModel.toggleGroupMember("bob-id")
        viewModel.createPrivateGroup()

        val group = controller.publishedGroups.single()
        assertEquals("Trip crew", group.name)
        assertEquals(setOf("local-peer", "bob-id"), group.members.map { it.peerId }.toSet())
        assertEquals(group.id, viewModel.uiState.value.selectedPeerId)
        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
    }

    @Test
    fun nonMembersDoNotStoreOrAcknowledgePrivateGroupMessages() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        makeViewModel(controller, store)

        controller.listener?.onMessageReceived(
            IncomingNearbyMessage(
                messageId = "private-message",
                conversationId = "unknown-private-group",
                senderId = "carol-id",
                senderName = "Carol",
                senderPhoneHash = "carol-phone-hash",
                text = "Members only",
                sentAt = 400L,
            ),
        )

        assertTrue(store.getMessages("unknown-private-group").isEmpty())
        assertTrue(controller.acknowledgements.isEmpty())
    }

    @Test
    fun invitedMemberStoresPrivateGroupMessages() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        makeViewModel(controller, store)
        val group = PrivateGroup(
            id = "private-group",
            name = "Crew",
            ownerId = "carol-id",
            createdAt = 350L,
            members = listOf(
                GroupMember("local-peer", "Alice"),
                GroupMember("carol-id", "Carol"),
            ),
        )

        controller.listener?.onGroupReceived(group)
        controller.listener?.onMessageReceived(
            IncomingNearbyMessage(
                messageId = "private-message",
                conversationId = group.id,
                senderId = "carol-id",
                senderName = "Carol",
                senderPhoneHash = "",
                text = "Members only",
                sentAt = 400L,
            ),
        )

        assertEquals("Members only", store.getMessages(group.id).single().text)
        assertEquals("carol-id" to "private-message", controller.acknowledgements.single())
    }

    @Test
    fun systemBackReturnsToChatsWithoutStoppingNearby() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        viewModel.updateDisplayName("Alice")
        viewModel.startChat()
        viewModel.openConversation(TEST_GROUP)

        viewModel.handleBack()
        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)

        viewModel.handleBack()
        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
        assertFalse(controller.stopped)
        assertTrue(viewModel.uiState.value.nearbyActive)
    }

    @Test
    fun importedContactLinksWhenItsPhoneIdentityAppearsOnMesh() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        val bobHash = requireNotNull(PhoneIdentity.hash("+1 202 555 0198"))

        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+1 202 555 0198")))
        controller.listener?.onMeshPeerFound(GroupMember("bob-peer", "Robert", bobHash))
        viewModel.beginCreateGroup()

        val contact = viewModel.uiState.value.groupContacts.single { it.phoneHash == bobHash }
        assertEquals("Bob", contact.name)
        assertEquals("bob-peer", contact.peerId)
        assertTrue(contact.availableOnMesh)
    }

    @Test
    fun groupInvitationRecognizesLocalMemberByPhoneHashAfterPeerIdChanges() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        val localHash = requireNotNull(PhoneIdentity.hash("+15551234567"))
        val group = PrivateGroup(
            id = "phone-matched-group",
            name = "Old contacts",
            ownerId = "owner-peer",
            createdAt = 600L,
            members = listOf(
                GroupMember("phone:$localHash", "Alice", localHash),
                GroupMember("owner-peer", "Owner", "owner-hash"),
            ),
        )

        controller.listener?.onGroupReceived(group)

        assertTrue(store.isGroupMember(group.id, "local-peer", localHash))
        assertTrue(viewModel.uiState.value.conversations.any { it.peerId == group.id })
    }

    @Test
    fun scannedCardIsReviewedThenSavedWithItsSocialDetails() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("maya-uid", "Maya", "maya-peer", email = "maya@example.com", username = "maya_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        val card = ContactProfile(
            displayName = "Maya",
            phoneNumber = "+61 412 345 678",
            email = "maya@example.com",
            instagramUrl = "https://instagram.com/maya",
        )

        viewModel.importScannedContactCard(ContactCardCodec.encode(card))

        assertEquals(ChatScreen.EDITING_CONTACT, viewModel.uiState.value.screen)
        assertEquals(ContactSource.QR, viewModel.uiState.value.contactSourceDraft)
        viewModel.saveContact()

        val saved = store.getSavedContacts().single()
        assertEquals("Maya", saved.name)
        assertEquals("maya@example.com", saved.email)
        assertEquals("https://instagram.com/maya", saved.instagramUrl)
        assertEquals(ContactSource.QR, saved.source)
        assertEquals(ChatScreen.MANAGING_CONTACTS, viewModel.uiState.value.screen)
    }

    @Test
    fun invalidQrDoesNotCreateAContact() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)

        viewModel.importScannedContactCard("https://example.com/not-a-card")

        assertTrue(store.getSavedContacts().isEmpty())
        assertEquals("That QR code is not a BLAP contact card.", viewModel.uiState.value.error)
    }

    @Test
    fun savingProfileRefreshesTheAdvertisedMeshIdentity() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        viewModel.updateDisplayName("Alice")
        viewModel.startChat()

        viewModel.updateProfile(
            ContactProfile(displayName = "Alice B", phoneNumber = "+61 400 111 222"),
        )
        viewModel.saveProfile()

        assertTrue(controller.stopped)
        assertEquals("Alice B", controller.advertisedName)
        assertEquals(ChatScreen.SHOWING_MY_CARD, viewModel.uiState.value.screen)
    }

    @Test
    fun setupAndRelaunchDoNotRequireNearbyPermissions() {
        val controller = FakeNearbyChatController()
        val identity = FakeIdentityStore()
        val viewModel = makeViewModel(controller, identityStore = identity)
        viewModel.updateDisplayName("Alice")
        viewModel.completeSetup()
        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
        assertFalse(controller.advertisingStarted)
        val reopened = makeViewModel(FakeNearbyChatController(), identityStore = identity)
        assertEquals(ChatScreen.CHATS, reopened.uiState.value.screen)
        assertFalse(reopened.uiState.value.nearbyActive)
    }

    @Test
    fun backgroundConnectionsDoNotInterruptEditing() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        viewModel.beginAddContact()
        viewModel.updateContactName("Unsaved contact")
        controller.listener?.onConnectionInitiated(NearbyDevice("bob-endpoint", "Bob"), "1234")
        controller.listener?.onConnected(ConnectedPeer("bob", "bob-endpoint", "Bob"))
        assertEquals(ChatScreen.EDITING_CONTACT, viewModel.uiState.value.screen)
        assertEquals("Unsaved contact", viewModel.uiState.value.contactNameDraft)
        assertEquals(1, viewModel.uiState.value.directConnectionCount)
    }

    @Test
    fun requestedConnectionOpensOnlyTheRequestedPeer() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        controller.listener?.onDeviceFound(NearbyDevice("bob-endpoint", "Bob"))
        viewModel.connectToDevice("bob-endpoint")
        controller.listener?.onConnected(ConnectedPeer("carol", "carol-endpoint", "Carol"))
        assertEquals(ChatScreen.CONNECTING, viewModel.uiState.value.screen)
        controller.listener?.onConnected(ConnectedPeer("bob", "bob-endpoint", "Bob"))
        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
        assertEquals("bob", viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun failedConnectionReturnsToChatsWithAnError() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        controller.listener?.onDeviceFound(NearbyDevice("bob-endpoint", "Bob"))
        viewModel.connectToDevice("bob-endpoint")
        controller.listener?.onError("Connection declined")
        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
        assertEquals("Connection declined", viewModel.uiState.value.error)
    }

    @Test
    fun profileCancelDiscardsEditsAndReturnsToSettings() {
        val identity = FakeIdentityStore().apply { saveDisplayName("Alice") }
        val viewModel = makeViewModel(FakeNearbyChatController(), identityStore = identity)
        viewModel.showSettings()
        viewModel.editProfile()
        viewModel.updateProfile(ContactProfile("Changed", "+61400111222"))
        assertEquals("Alice", viewModel.uiState.value.displayName)
        viewModel.handleBack()
        assertEquals(ChatScreen.SETTINGS, viewModel.uiState.value.screen)
        assertEquals("Alice", viewModel.uiState.value.displayName)
        assertEquals("Alice", identity.getDisplayName())
        assertEquals(null, viewModel.uiState.value.profileDraft)
    }

    @Test
    fun savingAnOfflineProfileDoesNotStartNearby() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        viewModel.showSettings()
        viewModel.editProfile()
        viewModel.updateProfile(ContactProfile("Alice", "+61400111222"))
        viewModel.saveProfile()
        assertEquals("Alice", viewModel.uiState.value.displayName)
        assertEquals(ChatScreen.SETTINGS, viewModel.uiState.value.screen)
        assertFalse(controller.advertisingStarted)
    }

    @Test
    fun draftsStayWithTheirConversationAndClearOnlyAfterSending() {
        val store = FakeChatStore().apply { savePeer("bob", "Bob") }
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation("bob")
        viewModel.updateMessageDraft("For Bob")
        viewModel.showConversationList()
        viewModel.openConversation(TEST_GROUP)
        viewModel.updateMessageDraft("For everyone")
        viewModel.sendMessage("For everyone")
        assertEquals("For Bob", viewModel.uiState.value.messageDrafts["bob"])
        assertFalse(viewModel.uiState.value.messageDrafts.containsKey(TEST_GROUP))
        viewModel.openConversation("bob")
        assertEquals("For Bob", viewModel.uiState.value.messageDrafts["bob"])
    }

    @Test
    fun matchedContactCanOpenAnOfflineConversation() {
        val store = FakeChatStore()
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller, store)
        viewModel.importDeviceContacts(listOf(DeviceContact("Bobby", "+12025550198")))
        val hash = requireNotNull(PhoneIdentity.hash("+12025550198"))
        controller.listener?.onMeshPeerFound(GroupMember("bob", "Robert", hash))
        val contact = viewModel.uiState.value.savedContacts.single()
        assertEquals("bob", contact.linkedPeerId)
        viewModel.messageContact(contact.id)
        viewModel.sendMessage("Hello")
        assertEquals("bob", viewModel.uiState.value.selectedPeerId)
        assertEquals("Bobby", viewModel.uiState.value.conversations.single { it.peerId == "bob" }.name)
        assertEquals(MessageStatus.PENDING, store.getMessages("bob").single().status)
    }

    @Test
    fun changingContactPhoneWithoutLookupDoesNotOverwriteTheSavedContact() {
        val store = FakeChatStore()
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller, store)
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        controller.listener?.onMeshPeerFound(GroupMember("bob", "Bob", requireNotNull(PhoneIdentity.hash("+12025550198"))))
        viewModel.openContact(viewModel.uiState.value.savedContacts.single().id)
        viewModel.updateContactPhone("+61400111222")
        viewModel.saveContact()
        assertEquals("bob", store.getSavedContacts().single().linkedPeerId)
        assertEquals("+12025550198", store.getSavedContacts().single().phoneNumber)
        assertNotNull(viewModel.uiState.value.error)
    }

    @Test
    fun groupSettingsRetainMembersMissingFromContacts() {
        val store = FakeChatStore()
        store.saveGroup(PrivateGroup("group", "Friends", "local-peer", 1L,
            listOf(GroupMember("local-peer", "Alice"), GroupMember("old-peer", "Old friend"))))
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation("group")
        viewModel.beginGroupSettings()
        assertTrue(viewModel.uiState.value.canEditGroup)
        viewModel.updateGroupName("Renamed")
        viewModel.saveGroupSettings()
        assertEquals(setOf("local-peer", "old-peer"), store.getGroups().single().members.map { it.peerId }.toSet())
    }

    @Test
    fun nonOwnerSeesReadOnlyGroupSettings() {
        val store = FakeChatStore()
        store.saveGroup(PrivateGroup("group", "Friends", "bob", 1L,
            listOf(GroupMember("local-peer", "Alice"), GroupMember("bob", "Bob"))))
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation("group")
        viewModel.beginGroupSettings()
        assertFalse(viewModel.uiState.value.canEditGroup)
        viewModel.updateGroupName("Renamed")
        viewModel.saveGroupSettings()
        assertEquals("Friends", store.getGroups().single().name)
    }

    @Test
    fun recentMessagesAppearBeforeEmptyConversationsAfterReload() {
        val store = FakeChatStore()
        store.savePeer("bob", "Bob")
        store.savePeer("carol", "Carol")
        store.saveMessage(ChatMessage("m1", "carol", "Latest", MessageAuthor.PEER, 100L, MessageStatus.DELIVERED))
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        assertEquals("carol", viewModel.uiState.value.conversations.first().peerId)
    }

    @Test
    fun unavailableNearbyCanBeRetriedWithoutLosingTheScreen() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)
        viewModel.updateDisplayName("Alice")
        viewModel.startChat()
        viewModel.showSettings()
        controller.listener?.onNearbyUnavailable("Bluetooth is unavailable")
        assertFalse(viewModel.uiState.value.nearbyActive)
        assertEquals(ChatScreen.SETTINGS, viewModel.uiState.value.screen)
        assertEquals("Bluetooth is unavailable", viewModel.uiState.value.error)
        viewModel.startChat()
        assertTrue(viewModel.uiState.value.nearbyActive)
    }

    @Test
    fun turningOffNearbyPreservesTheOpenChatAndDraft() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.updateDisplayName("Alice")
        viewModel.startChat()
        viewModel.openConversation(TEST_GROUP)
        viewModel.updateMessageDraft("Still writing")
        viewModel.stopChat()
        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
        assertEquals("Still writing", viewModel.uiState.value.messageDrafts[TEST_GROUP])
        assertFalse(viewModel.uiState.value.nearbyActive)
    }

    @Test
    fun phoneOnlyContactCanSendOnlineWithoutNearbyPairing() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val nearby = FakeNearbyChatController()
        val viewModel = makeViewModel(nearby, store, cloud = cloud)
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        viewModel.accountChanged("alice-uid")
        viewModel.messageContact(viewModel.uiState.value.savedContacts.single().id)

        viewModel.sendMessage("Hello online")

        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertEquals("Hello online", cloud.directMessages.single().second.text)
        assertTrue(nearby.sentMessages.isEmpty())
        assertTrue(store.getCloudPendingMessages().isEmpty())
    }

    @Test
    fun manuallyEnteredNationalZeroMatchesTheSameOnlineAccount() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+61412345678")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.beginAddContact()
        viewModel.updateContactDraft(ContactProfile(displayName = "Bob", phoneNumber = "+61 0412 345 678"))
        viewModel.saveContact()
        viewModel.accountChanged("alice-uid")
        val contact = store.getSavedContacts().single()
        assertEquals("+61412345678", contact.phoneNumber)

        viewModel.messageContact(contact.id)
        viewModel.sendMessage("Hello Bob")

        assertEquals("bob-uid", cloud.directMessages.single().first)
    }

    @Test
    fun sharedCardPhoneAndOnlineLookupPhoneStaySeparate() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.updateDisplayName("Alice")
        viewModel.completeSetup()
        viewModel.showDiscoverySettings()
        viewModel.updateDiscoveryPhone("+12025550198")
        viewModel.updateDiscoveryEnabled(true)
        viewModel.saveDiscoverySettings()
        viewModel.editProfile()
        val cardDraft = requireNotNull(viewModel.uiState.value.profileDraft)
        viewModel.updateProfile(cardDraft.copy(phoneNumber = "+61412345678"))
        viewModel.saveProfile()

        assertEquals("+61412345678", viewModel.uiState.value.phoneNumber)
        assertEquals("+12025550198", viewModel.uiState.value.profileLookupPhoneNumber)
        assertTrue(viewModel.uiState.value.profileDiscoverableByPhone)
    }

    @Test
    fun numberLookupStillWorksWhenSavedEmailHasNoOnlineMatch() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.beginAddContact()
        viewModel.updateContactDraft(ContactProfile(
            displayName = "Bob", email = "old@example.com", phoneNumber = "+12025550198",
        ))
        viewModel.saveContact()
        viewModel.accountChanged("alice-uid")
        viewModel.messageContact(store.getSavedContacts().single().id)

        viewModel.sendMessage("Hello from my contact card")

        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertEquals("bob-uid", store.getSavedContacts().single().cloudUserId)
        assertEquals("bob_uid", store.getSavedContacts().single().username)
    }

    @Test
    fun cloudAndNearbyCopiesOfOneDirectMessageAreStoredOnce() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val nearby = FakeNearbyChatController()
        val viewModel = makeViewModel(nearby, store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        val message = CloudChatMessage("same-id", "bob-uid", "bob-id", "Bob", "Hi", 100L)

        cloud.listener?.onDirectMessage("bob-uid", message)
        nearby.listener?.onConnected(ConnectedPeer("bob-id", "bob-endpoint", "Bob", requireNotNull(PhoneIdentity.hash("+12025550198"))))
        nearby.listener?.onMessageReceived(
            IncomingNearbyMessage("same-id", "bob-id", "bob-id", "Bob", requireNotNull(PhoneIdentity.hash("+12025550198")), "Hi", 100L),
        )

        assertEquals(1, store.getMessages("account:bob-uid").size)
        assertEquals("Hi", store.getMessages("account:bob-uid").single().text)
    }

    @Test
    fun incomingReplyIsStoredWithoutFetchingTheSenderAccountAgain() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        viewModel.messageContact(store.getSavedContacts().single().id)
        viewModel.sendMessage("Hello Bob")
        val peerId = requireNotNull(viewModel.uiState.value.selectedPeerId)
        val replyTime = store.getMessages(peerId).single().sentAt + 1
        cloud.failAccountFetch = true

        cloud.listener?.onDirectMessage("bob-uid",
            CloudChatMessage("reply-id", "bob-uid", "bob-id", "Bob", "Hello Alice", replyTime))

        assertEquals(listOf("Hello Bob", "Hello Alice"), store.getMessages(peerId).map { it.text })
        assertEquals(MessageAuthor.PEER, store.getMessages(peerId).last().author)
        assertTrue(viewModel.uiState.value.conversations.single { it.peerId == peerId }.onlineAccountLinked)
    }

    @Test
    fun phoneOnlyPrivateGroupUploadsAndReceivesOnline() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.updateDisplayName("Alice")
        viewModel.accountChanged("alice-uid")
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        viewModel.beginCreateGroup()
        viewModel.updateGroupName("Friends")
        viewModel.toggleGroupMember(viewModel.uiState.value.groupContacts.single().peerId)

        viewModel.createPrivateGroup()
        viewModel.sendMessage("Group hello")

        assertEquals(setOf("alice-uid", "bob-uid"),
            cloud.groups.single().members.map(CloudGroupMember::uid).toSet())
        assertEquals("Group hello", cloud.groupMessages.single().second.text)
    }

    @Test
    fun queuedPhoneOnlyMessageUploadsWhenContactAppearsOnline() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        viewModel.messageContact(viewModel.uiState.value.savedContacts.single().id)
        viewModel.sendMessage("Saved while offline")
        assertTrue(cloud.directMessages.isEmpty())

        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        viewModel.accountChanged("alice-uid")

        assertEquals("Saved while offline", cloud.directMessages.single().second.text)
        assertTrue(store.getCloudPendingMessages().isEmpty())
    }

    @Test
    fun scannedCardPairsPeerIdWithoutNearbyConnection() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        val peerId = "20aecc56-8f17-44b1-ac58-338417b7d320"
        viewModel.importScannedContactCard(
            ContactCardCodec.encode(ContactProfile("Bob", "+12025550198"), peerId),
        )
        viewModel.saveContact()

        assertEquals(peerId, store.getSavedContacts().single().linkedPeerId)
    }

    @Test
    fun scannedUsernameOnlyCardCanStartOnlineChatWithoutPhoneLookup() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importScannedContactCard(ContactCardCodec.encode(
            ContactProfile(displayName = "Bob", username = "bob_user")))
        viewModel.saveContact()

        val contact = store.getSavedContacts().single()
        assertEquals("bob_user", contact.username)
        viewModel.messageContact(contact.id)
        viewModel.sendMessage("Hello Bob")

        assertEquals("bob-uid", store.getSavedContacts().single().cloudUserId)
        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertEquals("account:bob-uid", viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun scannedQrUsesUsernameWhenCardPhoneIsNotPublishedForLookup() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val peerId = "20aecc56-8f17-44b1-ac58-338417b7d320"
        cloud.addAccount("bob-uid", "Bob", peerId, username = "bob_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importScannedContactCard(ContactCardCodec.encode(
            ContactProfile(displayName = "Bob", phoneNumber = "+12025550198", username = "bob_user"),
            peerId))
        viewModel.saveContact()

        viewModel.messageContact(store.getSavedContacts().single().id)
        viewModel.sendMessage("Hello from the QR card")

        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertEquals("account:bob-uid", viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun replyToUnsavedOnlineSenderUsesUidFromReceivedMessage() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "old-peer-id", username = "bob_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        cloud.listener?.onDirectMessage("bob-uid", CloudChatMessage(
            "incoming-id", "bob-uid", "bob-other-device", "Bob", "Hi Alice", 100L))

        viewModel.openConversation("account:bob-uid")
        viewModel.sendMessage("Hi Bob")

        assertEquals("bob-uid", cloud.directMessages.single().first)
    }

    @Test
    fun severalPhoneMatchesOfferUsernameChoiceBeforeMessaging() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", phone = "+12025550198", username = "bob_user")
        cloud.addAccount("sam-uid", "Sam", "sam-peer", phone = "+12025550198", username = "sam_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importDeviceContacts(listOf(DeviceContact("Friend", "+12025550198")))

        val contact = store.getSavedContacts().single()
        viewModel.messageContact(contact.id)
        assertEquals(setOf("bob_user", "sam_user"),
            viewModel.uiState.value.accountCandidates.map(CloudAccount::username).toSet())
        viewModel.selectOnlineAccount("sam-uid")
        viewModel.sendMessage("Hello Sam")

        assertEquals("sam-uid", store.getSavedContacts().single().cloudUserId)
        assertEquals("sam_user", store.getSavedContacts().single().username)
        assertEquals("sam-uid", cloud.directMessages.single().first)
    }

    @Test
    fun usernameOnlyContactCanJoinOnlinePrivateGroup() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.updateDisplayName("Alice")
        viewModel.accountChanged("alice-uid")
        viewModel.beginAddContact()
        viewModel.updateContactDraft(ContactProfile(displayName = "Bob", username = "bob_user"))
        viewModel.saveContact()

        viewModel.beginCreateGroup()
        viewModel.updateGroupName("Friends")
        viewModel.toggleGroupMember(viewModel.uiState.value.groupContacts.single().peerId)
        viewModel.createPrivateGroup()

        assertEquals(setOf("alice-uid", "bob-uid"),
            cloud.groups.single().members.map(CloudGroupMember::uid).toSet())
    }

    @Test
    fun multipleEmailOnlyContactsCanBeSavedAndSelectedForGroups() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("alex-uid", "Alex", "alex-peer", email = "alex@example.com", username = "alex_user")
        cloud.addAccount("sam-uid", "Sam", "sam-peer", email = "sam@example.com", username = "sam_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        listOf("alex@example.com", "sam@example.com").forEach { email ->
            viewModel.beginAddContact()
            viewModel.updateContactDraft(ContactProfile(displayName = email.substringBefore('@'), email = email))
            viewModel.saveContact()
        }

        assertEquals(2, store.getSavedContacts().size)
        assertTrue(store.getSavedContacts().all { it.phoneNumber.isBlank() && it.phoneHash.isBlank() })
        viewModel.beginCreateGroup()
        assertEquals(2, viewModel.uiState.value.groupContacts.map { it.peerId }.distinct().size)
    }

    @Test
    fun googleAccountEmailCanBeSavedWithoutPhone() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("ari-uid", "Ari", "ari-peer", email = "ari@example.com", username = "ari_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.beginAddContact()
        viewModel.updateContactDraft(ContactProfile(displayName = "Ari", googleAccountEmail = "Ari@Example.com"))
        viewModel.saveContact()

        assertEquals("ari@example.com", store.getSavedContacts().single().googleAccountEmail)
    }

    @Test
    fun pairedEmailOnlyContactWithoutOnlineAccountStaysPending() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        val peerId = "20aecc56-8f17-44b1-ac58-338417b7d320"
        viewModel.importScannedContactCard(
            ContactCardCodec.encode(ContactProfile(displayName = "Bob", email = "bob@example.com"), peerId),
        )
        viewModel.saveContact()
        viewModel.accountChanged("alice-uid")
        viewModel.messageContact(store.getSavedContacts().single().id)
        viewModel.sendMessage("Saved locally")

        assertEquals(peerId, store.getSavedContacts().single().linkedPeerId)
        assertTrue(cloud.directMessages.isEmpty())
    }

    @Test
    fun replyPollVoteEditAndDeleteUseTheSameOutbox() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation(TEST_GROUP)
        viewModel.sendMessage("Original")
        val original = store.getMessages(TEST_GROUP).single()

        viewModel.sendReply(original.id, "Answer")
        viewModel.createPoll("Choose a place", listOf("Park", "Library"))
        val poll = store.getMessages(TEST_GROUP).first {
            ChatFeatures.decode(it.text) is ChatContent.Poll
        }
        viewModel.voteInPoll(poll.id, 1)
        viewModel.editMessage(original.id, "Updated")
        var shown = ChatTimeline.present(store.getMessages(TEST_GROUP))
        assertEquals(ChatContent.Text("Updated"), shown.first { it.message.id == original.id }.content)
        assertEquals(listOf(0, 1), shown.first { it.message.id == poll.id }.votes)
        assertTrue(shown.any { it.content is ChatContent.Reply })

        viewModel.deleteMessage(original.id)
        shown = ChatTimeline.present(store.getMessages(TEST_GROUP))
        assertTrue(shown.first { it.message.id == original.id }.deleted)
    }

    @Test
    fun sendVoiceStoresAVoiceMessage() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation(TEST_GROUP)

        viewModel.sendVoice(1_200, byteArrayOf(1, 2, 3, 4))

        val stored = store.getMessages(TEST_GROUP).single()
        val content = ChatFeatures.decode(stored.text)
        assertTrue(content is ChatContent.Voice)
        assertEquals(1_200, (content as ChatContent.Voice).durationMs)
        assertEquals("Voice message", ChatFeatures.preview(stored.text))
        assertEquals("Voice message", viewModel.uiState.value.conversations
            .first { it.peerId == TEST_GROUP }.lastMessage)
    }

    @Test
    fun oversizedVoiceNoteIsRejected() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation(TEST_GROUP)

        viewModel.sendVoice(1_000, ByteArray(ChatViewModel.MAX_VOICE_ENCODED_LENGTH))

        assertTrue(store.getMessages(TEST_GROUP).isEmpty())
        assertEquals("This message is too long.", viewModel.uiState.value.error)
    }

    @Test
    fun shortVoiceNoteIsRejected() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation(TEST_GROUP)

        viewModel.sendVoice(200, byteArrayOf(1, 2, 3))

        assertTrue(store.getMessages(TEST_GROUP).isEmpty())
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun replyToVoiceUsesVoiceMessageExcerpt() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.openConversation(TEST_GROUP)
        viewModel.sendVoice(800, byteArrayOf(9, 8, 7))
        val voice = store.getMessages(TEST_GROUP).single()

        viewModel.sendReply(voice.id, "Got it")

        val reply = ChatTimeline.present(store.getMessages(TEST_GROUP))
            .first { it.content is ChatContent.Reply }.content as ChatContent.Reply
        assertEquals("Voice message", reply.excerpt)
    }

    @Test
    fun structuredChatActionsAreUploadedThroughTheOnlineChat() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        viewModel.messageContact(store.getSavedContacts().single().id)
        viewModel.createPoll("Meet where?", listOf("Park", "Library"))
        val poll = store.getMessages(requireNotNull(viewModel.uiState.value.selectedPeerId)).single()
        viewModel.voteInPoll(poll.id, 0)

        assertEquals(2, cloud.directMessages.size)
        assertTrue(ChatFeatures.decode(cloud.directMessages[0].second.text) is ChatContent.Poll)
        assertEquals(ChatContent.Vote(poll.id, 0), ChatFeatures.decode(cloud.directMessages[1].second.text))
    }

    @Test
    fun incomingMessagesCanAlertButCannotBeEditedLocally() {
        val store = FakeChatStore()
        val notifier = RecordingNotifier()
        val viewModel = makeViewModel(FakeNearbyChatController(), store, notifier = notifier)
        viewModel.openConversation(TEST_GROUP)
        val incoming = IncomingNearbyMessage(
            "incoming", TEST_GROUP, "bob", "Bob", "", "Hello", System.currentTimeMillis(),
        )
        viewModel.onMessageReceived(incoming)
        viewModel.editMessage("incoming", "Wrong")

        assertEquals(1, store.getMessages(TEST_GROUP).size)
        assertEquals(1, notifier.incoming.size)
        assertEquals(ConversationType.PRIVATE_GROUP, notifier.incoming.single().second)
    }

    @Test
    fun chatHeaderProfileReturnsToTheOpenConversation() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", email = "bob@example.com", username = "bob_user")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        viewModel.accountChanged("alice-uid")
        viewModel.beginAddContact()
        viewModel.updateContactDraft(ContactProfile(displayName = "Bob", email = "bob@example.com"))
        viewModel.saveContact()
        val contact = store.getSavedContacts().single()
        val peerId = requireNotNull(ContactIdentity.conversationId(contact))
        viewModel.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob"))
        viewModel.showConversationList()
        viewModel.openConversation(peerId)

        viewModel.openCurrentChatProfile()
        assertEquals(ChatScreen.CONTACT_PROFILE, viewModel.uiState.value.screen)
        assertEquals(contact.id, viewModel.uiState.value.selectedContactId)
        viewModel.handleBack()
        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
    }

    @Test
    fun unsavedChatContactCanBeSavedFromTheHeader() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
        viewModel.onConnected(ConnectedPeer("bob-id", "endpoint", "Bob"))
        viewModel.openConversation("bob-id")
        viewModel.openCurrentChatProfile()
        assertNull(viewModel.uiState.value.selectedContactId)

        viewModel.saveCurrentChatContact()
        assertEquals(ChatScreen.EDITING_CONTACT, viewModel.uiState.value.screen)
        viewModel.saveContact()

        assertEquals(ChatScreen.CONTACT_PROFILE, viewModel.uiState.value.screen)
        assertEquals("bob-id", store.getSavedContacts().single().linkedPeerId)
        assertEquals(store.getSavedContacts().single().id, viewModel.uiState.value.selectedContactId)
    }

    @Test
    fun incomingOnlineChatShowsAndSavesTheSendersUniqueUsername() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController().apply {
            addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        }
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        cloud.listener?.onDirectMessage("bob-uid",
            CloudChatMessage("incoming", "bob-uid", "bob-peer", "Bob", "Hello", 100L))

        assertEquals("bob_user", vm.uiState.value.conversations.single().username)
        vm.openConversation("account:bob-uid")
        vm.openCurrentChatProfile()
        assertNull(vm.uiState.value.selectedContactId)
        vm.saveCurrentChatContact()
        assertEquals("bob_user", vm.uiState.value.contactUsernameDraft)
        cloud.failAccountFetch = true
        vm.saveContact()

        assertEquals("bob_user", store.getSavedContacts().single().username)
        assertEquals("bob-uid", store.getSavedContacts().single().cloudUserId)
        assertEquals("bob-peer", store.getSavedContacts().single().linkedPeerId)
        vm.messageContact(store.getSavedContacts().single().id)
        vm.sendMessage("Reply")
        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertEquals(listOf("account:bob-uid"), store.getConversations().map { it.peerId })
    }

    @Test
    fun sentMessageEchoLoadsTheRecipientsUsernameNotTheSenders() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController().apply {
            addAccount("alice-uid", "Alice", "local-peer", username = "alice_user")
            addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        }
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        cloud.listener?.onDirectMessage("bob-uid",
            CloudChatMessage("echo", "alice-uid", "local-peer", "Alice", "Hello", 100L))

        assertEquals("bob_user", vm.uiState.value.conversations.single().username)
        assertEquals(listOf("bob-uid"), cloud.accountFetches)
    }

    @Test
    fun offlineNearbyChatKeepsUsernameAfterDisconnectingAndReopening() {
        val store = FakeChatStore()
        val vm = makeViewModel(FakeNearbyChatController(), store)
        vm.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob", username = "bob_user"))
        vm.onDisconnected("bob-peer")
        val reopened = makeViewModel(FakeNearbyChatController(), store)
        reopened.openConversation("bob-peer")
        reopened.openCurrentChatProfile()
        assertEquals("bob_user", reopened.uiState.value.conversations.single().username)
        reopened.saveCurrentChatContact()
        assertEquals("bob_user", reopened.uiState.value.contactUsernameDraft)
        reopened.saveContact()
        assertEquals("bob_user", store.getSavedContacts().single().username)
    }

    @Test
    fun failedPublicCardLookupDoesNotBlockMessagesOrEraseCachedUsername() {
        val store = FakeChatStore().apply {
            savePeer("account:bob-uid", "Bob")
            savePeerUsername("account:bob-uid", "bob_user")
        }
        val cloud = FakeCloudChatController().apply { failAccountFetch = true }
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        cloud.listener?.onDirectMessage("bob-uid",
            CloudChatMessage("incoming", "bob-uid", "bob-peer", "Bob", "Hello", 100L))

        assertEquals("Hello", store.getMessages("account:bob-uid").single().text)
        assertEquals("bob_user", vm.uiState.value.conversations.single().username)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun delayedPublicCardCannotPopulateADifferentContactDraft() {
        val store = FakeChatStore().apply { savePeer("account:bob-uid", "Bob") }
        val ready = CompletableDeferred<Unit>()
        val cloud = FakeCloudChatController().apply {
            addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
            beforeAccountFetch = { ready.await() }
        }
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.openConversation("account:bob-uid")
        vm.saveCurrentChatContact()
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile(displayName = "Carol", username = "carol_user"))
        ready.complete(Unit)

        assertEquals("Carol", vm.uiState.value.contactNameDraft)
        assertEquals("carol_user", vm.uiState.value.contactUsernameDraft)
        assertTrue(store.getSavedContacts().isEmpty())
    }

    private val testSessions = mutableListOf<MessagingSession>()

    @Test
    fun conflictingPhoneAndEmailMatchesRequireChoosingAnAccount() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", email = "bob@example.com", username = "bob_user")
        cloud.addAccount("sam-uid", "Sam", "sam-peer", phone = "+12025550198", username = "sam_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Friend", phoneNumber = "+12025550198", email = "bob@example.com"))
        vm.saveContact()
        assertTrue(store.getSavedContacts().isEmpty())
        assertEquals(setOf("bob_user", "sam_user"), vm.uiState.value.accountCandidates.map { it.username }.toSet())
        vm.cancelAccountSelection()
        assertTrue(store.getSavedContacts().isEmpty())
    }

    @Test
    fun sharedPhoneAccountsRemainSeparateContactsChatsAndGroupChoices() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", phone = "+12025550198", username = "bob_user")
        cloud.addAccount("sam-uid", "Sam", "sam-peer", phone = "+12025550198", username = "sam_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        listOf("bob_user", "sam_user").forEach { username ->
            vm.beginAddContact()
            vm.updateContactDraft(ContactProfile(username, phoneNumber = "+12025550198", username = username))
            vm.saveContact()
            vm.messageContact(store.getSavedContacts().single { it.username == username }.id)
            vm.sendMessage(username)
        }
        assertEquals(2, store.getSavedContacts().size)
        assertEquals(listOf("bob_user"), store.getMessages("account:bob-uid").map { it.text })
        assertEquals(listOf("sam_user"), store.getMessages("account:sam-uid").map { it.text })
        vm.beginCreateGroup()
        assertEquals(setOf("bob_user", "sam_user"), vm.uiState.value.groupContacts.map { it.username }.toSet())
    }

    @Test
    fun manualContactWaitsForLookupBeforeBeingSaved() = runBlocking {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val ready = CompletableDeferred<Unit>()
        cloud.beforeLookup = { ready.await() }
        cloud.addAccount("bob-uid", "Bob", "bob-peer", email = "bob@example.com", username = "bob_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile(displayName = "Bob", email = "bob@example.com"))
        vm.saveContact()
        vm.saveContact()
        assertTrue(store.getSavedContacts().isEmpty())
        assertTrue(vm.uiState.value.savingContact)
        ready.complete(Unit)
        assertEquals("bob_user", store.getSavedContacts().single().username)
        assertEquals("bob-uid", store.getSavedContacts().single().cloudUserId)
        assertFalse(vm.uiState.value.savingContact)
    }

    @Test
    fun failedManualLookupDoesNotSaveAnUnlinkedContact() {
        val store = FakeChatStore()
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = FakeCloudChatController())
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile(displayName = "Nobody", username = "missing_user"))
        vm.saveContact()
        assertTrue(store.getSavedContacts().isEmpty())
        assertEquals(ChatScreen.EDITING_CONTACT, vm.uiState.value.screen)
        assertTrue(vm.uiState.value.error.orEmpty().contains("Contact not saved"))
    }

    @Test
    fun ambiguousManualLookupSavesOnlyTheChosenUsername() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", phone = "+12025550198", username = "bob_user")
        cloud.addAccount("sam-uid", "Sam", "sam-peer", phone = "+12025550198", username = "sam_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile(displayName = "Friend", phoneNumber = "+12025550198"))
        vm.saveContact()
        assertTrue(store.getSavedContacts().isEmpty())
        assertEquals(2, vm.uiState.value.accountCandidates.size)
        vm.selectOnlineAccount("sam-uid")
        assertEquals("sam_user", store.getSavedContacts().single().username)
        assertEquals("sam-uid", store.getSavedContacts().single().cloudUserId)
    }

    @Test
    fun emailPhoneAndUsernameConvergeOnOneContactAndConversation() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", phone = "+12025550198", email = "bob@example.com", username = "bob_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        listOf(ContactProfile("Bob", email = "bob@example.com"),
            ContactProfile("Bob", phoneNumber = "+12025550198"),
            ContactProfile("Bob", username = "bob_user")).forEachIndexed { index, profile ->
            vm.beginAddContact()
            vm.updateContactDraft(profile)
            vm.saveContact()
            vm.messageContact(store.getSavedContacts().single().id)
            vm.sendMessage("Message $index")
        }
        assertEquals(1, store.getSavedContacts().size)
        assertEquals(listOf("account:bob-uid"), store.getConversations().filter {
            it.type == ConversationType.DIRECT }.map { it.peerId })
        assertEquals(3, store.getMessages("account:bob-uid").size)
    }

    @Test
    fun lookupMergesLegacyEmailAndPhoneChatsWithoutLosingMessages() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", phone = "+12025550198", email = "bob@example.com", username = "bob_user")
        val hash = requireNotNull(PhoneIdentity.hash("+12025550198"))
        val emailId = requireNotNull(ContactIdentity.localPeerId("", "bob@example.com", ""))
        listOf("phone:$hash", emailId, "bob-peer").forEachIndexed { index, id ->
            store.savePeer(id, "Bob")
            store.saveMessage(ChatMessage("old-$index", id, "Old $index", MessageAuthor.PEER, index.toLong(), MessageStatus.DELIVERED))
        }
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Bob", phoneNumber = "+12025550198", email = "bob@example.com"))
        vm.saveContact()
        assertEquals(3, store.getMessages("account:bob-uid").size)
        assertEquals(1, store.getConversations().count { it.type == ConversationType.DIRECT })
    }

    @Test
    fun savedAccountCanSendWhenAnotherProfileFetchWouldFail() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Bob", username = "bob_user"))
        vm.saveContact()
        cloud.failAccountFetch = true
        vm.messageContact(store.getSavedContacts().single().id)
        vm.sendMessage("Still linked")
        assertEquals("bob-uid", cloud.directMessages.single().first)
        assertFalse(vm.uiState.value.notice.orEmpty().contains("No online account matched"))
    }

    @Test
    fun accountConversationRoutesNearbyMessagesToThePairedDevice() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val nearby = FakeNearbyChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val vm = makeViewModel(nearby, store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Bob", username = "bob_user"))
        vm.saveContact()
        vm.onConnected(ConnectedPeer("bob-peer", "endpoint", "Bob"))
        vm.messageContact(store.getSavedContacts().single().id)
        vm.sendMessage("One chat, both routes")
        assertEquals("bob-peer", nearby.sentMessages.single().peerId)
        assertEquals("account:bob-uid", vm.uiState.value.selectedPeerId)
        vm.onMessageReceived(IncomingNearbyMessage("nearby-reply", "bob-peer", "bob-peer", "Bob", "", "Reply", 100L))
        assertEquals(2, store.getMessages("account:bob-uid").size)
    }

    @Test
    fun deleteChatKeepsTheContactAndSuppressesCloudReplay() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Bob", username = "bob_user"))
        vm.saveContact()
        vm.messageContact(store.getSavedContacts().single().id)
        vm.sendMessage("Old")
        vm.updateMessageDraft("Discard this")
        vm.deleteChat()
        assertEquals(ChatScreen.CHATS, vm.uiState.value.screen)
        assertEquals(1, store.getSavedContacts().size)
        assertTrue(vm.uiState.value.messageDrafts.isEmpty())
        cloud.listener!!.onDirectMessage("bob-uid", CloudChatMessage("old-replay", "bob-uid", "bob-peer", "Bob", "Old", 1L))
        assertTrue(store.getMessages("account:bob-uid").isEmpty())
        assertTrue(store.getConversations().none { it.peerId == "account:bob-uid" })
        cloud.listener!!.onDirectMessage("bob-uid", CloudChatMessage("new", "bob-uid", "bob-peer", "Bob", "New", System.currentTimeMillis() + 1_000))
        assertEquals("New", store.getMessages("account:bob-uid").single().text)
        assertTrue(store.getConversations().any { it.peerId == "account:bob-uid" })
    }

    @Test
    fun closingTheEditorDiscardsAnInFlightLookup() = runBlocking {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        val ready = CompletableDeferred<Unit>()
        cloud.beforeLookup = { ready.await() }
        cloud.addAccount("bob-uid", "Bob", "bob-peer", username = "bob_user")
        val vm = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
        vm.accountChanged("alice-uid")
        vm.beginAddContact()
        vm.updateContactDraft(ContactProfile("Bob", username = "bob_user"))
        vm.saveContact()
        vm.closeContactEditor()
        ready.complete(Unit)
        assertTrue(store.getSavedContacts().isEmpty())
        assertEquals(ChatScreen.MANAGING_CONTACTS, vm.uiState.value.screen)
    }

    @org.junit.After
    fun closeTestSessions() { testSessions.forEach(MessagingSession::close) }

    /** Test-only convenience for existing end-to-end regression scenarios. Production has no forwarding facade. */
    private inner class MessagingTestHarness(
        nearbyChatController: NearbyTransport,
        chatStore: ChatStore,
        identityStore: IdentityStore,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        cloudChatController: CloudChatController? = null,
        chatNotifier: ChatNotifier = NoopChatNotifier,
        initialAccountId: String = "",
        privateProfileStore: PrivateProfileStore? = null,
    ) {
        val session = MessagingSession(ChatDependencies(nearbyChatController, chatStore, identityStore,
            accountId = initialAccountId, cloudChatController = cloudChatController,
            notifier = chatNotifier, privateProfileStore = privateProfileStore, ioDispatcher = ioDispatcher))
        val chat = ChatViewModel(session)
        val contacts = ContactsViewModel(session)
        val groups = GroupsViewModel(session)
        val profile = ProfileViewModel(session)
        val uiState = session.uiState
        init { testSessions += session }

        fun startChat() = chat.startChat()
        fun connectToDevice(endpointId: String) = chat.connectToDevice(endpointId)
        fun updateConversationSearch(query: String) = chat.updateConversationSearch(query)
        fun sendMessage(text: String) = chat.sendMessage(text)
        fun sendReply(targetId: String, text: String) = chat.sendReply(targetId, text)
        fun createPoll(question: String, options: List<String>) = chat.createPoll(question, options)
        fun voteInPoll(pollId: String, option: Int) = chat.voteInPoll(pollId, option)
        fun editMessage(messageId: String, text: String) = chat.editMessage(messageId, text)
        fun deleteMessage(messageId: String) = chat.deleteMessage(messageId)
        fun deleteChat() = chat.deleteChat()
        fun sendVoice(durationMs: Int, audio: ByteArray) = chat.sendVoice(durationMs, audio)
        fun disconnect(peerId: String) = chat.disconnect(peerId)
        fun dismissError() = chat.dismissError()
        fun updateMessageDraft(text: String) = chat.updateMessageDraft(text)
        fun updateVenueStatus(message: String, checking: Boolean = false) = chat.updateVenueStatus(message, checking)
        fun onDeviceFound(device: NearbyDevice) = chat.onDeviceFound(device)
        fun onDeviceLost(endpointId: String) = chat.onDeviceLost(endpointId)
        fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String) = chat.onConnectionInitiated(device, authenticationDigits)
        fun onConnected(peer: ConnectedPeer) = chat.onConnected(peer)
        fun onMeshPeerFound(peer: GroupMember) = chat.onMeshPeerFound(peer)
        fun onMessageReceived(message: IncomingNearbyMessage) = chat.onMessageReceived(message)
        fun onMessageSent(peerId: String, messageId: String) = chat.onMessageSent(peerId, messageId)
        fun onMessageDelivered(peerId: String, messageId: String) = chat.onMessageDelivered(peerId, messageId)
        fun onDisconnected(peerId: String) = chat.onDisconnected(peerId)
        fun onError(message: String) = chat.onError(message)
        fun onNearbyUnavailable(message: String) = chat.onNearbyUnavailable(message)
        fun stopChat() = chat.stopChat()
        fun beginManageContacts() = contacts.beginManageContacts()
        fun beginAddContact() = contacts.beginAddContact()
        fun openContact(contactId: String) = contacts.openContact(contactId)
        fun openCurrentChatProfile() = contacts.openCurrentChatProfile()
        fun saveCurrentChatContact() = contacts.saveCurrentChatContact()
        fun closeCurrentChatProfile() = contacts.closeCurrentChatProfile()
        fun closeContactEditor() = contacts.closeContactEditor()
        fun messageContact(contactId: String) = contacts.messageContact(contactId)
        fun checkContactOnline(contactId: String) = contacts.checkContactOnline(contactId)
        fun selectOnlineAccount(uid: String) = contacts.selectOnlineAccount(uid)
        fun cancelAccountSelection() = contacts.cancelAccountSelection()
        fun updateContactDraft(profile: ContactProfile) = contacts.updateContactDraft(profile)
        fun importScannedContactCard(payload: String) = contacts.importScannedContactCard(payload)
        fun updateContactName(name: String) = contacts.updateContactName(name)
        fun updateContactPhone(phoneNumber: String) = contacts.updateContactPhone(phoneNumber)
        fun saveContact() = contacts.saveContact()
        fun deleteContact() = contacts.deleteContact()
        fun importDeviceContacts(contacts: List<DeviceContact>) = this.contacts.importDeviceContacts(contacts)
        fun updateContactSearch(query: String) = contacts.updateContactSearch(query)
        fun beginCreateGroup() = groups.beginCreateGroup()
        fun beginGroupSettings() = groups.beginGroupSettings()
        fun updateGroupName(name: String) = groups.updateGroupName(name)
        fun toggleGroupMember(peerId: String) = groups.toggleGroupMember(peerId)
        fun createPrivateGroup() = groups.createPrivateGroup()
        fun saveGroupSettings() = groups.saveGroupSettings()
        fun deleteCurrentGroup() = groups.deleteCurrentGroup()
        fun updateDisplayName(name: String) = profile.updateDisplayName(name)
        fun updatePhoneNumber(phoneNumber: String) = profile.updatePhoneNumber(phoneNumber)
        fun completeSetup() = profile.completeSetup()
        fun showMyCard() = profile.showMyCard()
        fun editProfile() = profile.editProfile()
        fun showDiscoverySettings() = profile.showDiscoverySettings()
        fun updateDiscoveryPhone(phoneNumber: String) = profile.updateDiscoveryPhone(phoneNumber)
        fun updateDiscoveryEnabled(enabled: Boolean) = profile.updateDiscoveryEnabled(enabled)
        fun cancelDiscoveryEdit() = profile.cancelDiscoveryEdit()
        fun saveDiscoverySettings() = profile.saveDiscoverySettings()
        fun updateProfile(profile: ContactProfile) = this.profile.updateProfile(profile)
        fun cancelProfileEdit() = profile.cancelProfileEdit()
        fun saveProfile() = profile.saveProfile()
        fun showSettings() = profile.showSettings()
        fun applyAccountProfile(username: String, displayName: String) = profile.applyAccountProfile(username, displayName)
        fun restorePrivateProfile(snapshot: PrivateProfileSnapshot?, username: String, displayName: String) = profile.restorePrivateProfile(snapshot, username, displayName)
        fun onDirectMessage(otherUid: String, message: CloudChatMessage) = session.cloudSync.onDirectMessage(otherUid, message)
        fun onPrivateGroup(group: CloudPrivateGroup) = session.cloudSync.onPrivateGroup(group)
        fun onGroupMessage(groupId: String, message: CloudChatMessage) = session.cloudSync.onGroupMessage(groupId, message)
        fun onCloudError(message: String) = session.cloudSync.onCloudError(message)
        fun openConversation(peerId: String) {
            if (peerId == TEST_GROUP && session.store.getGroups().none { it.id == TEST_GROUP }) {
                prepareTestGroup(session.store as FakeChatStore)
                session.persistence.reloadConversationsNow()
            }
            session.navigation.openConversation(peerId)
        }
        fun showConversationList() = session.navigation.showConversationList()
        fun handleBack() = session.navigation.handleBack()
        fun showError(message: String) = session.showError(message)
        fun showNotice(message: String) = session.showNotice(message)
        fun accountChanged(accountId: String) = session.cloudSync.accountChanged(accountId)
    }

    private fun makeViewModel(
        controller: FakeNearbyChatController,
        store: FakeChatStore = FakeChatStore(),
        identityStore: FakeIdentityStore = FakeIdentityStore(),
        cloud: FakeCloudChatController? = null,
        notifier: ChatNotifier = NoopChatNotifier,
    ): MessagingTestHarness {
        if (store.getMessages(TEST_GROUP).isNotEmpty()) prepareTestGroup(store)
        return MessagingTestHarness(controller, store, identityStore, Dispatchers.Unconfined,
            cloudChatController = cloud, chatNotifier = notifier)
    }

    private class RecordingNotifier : ChatNotifier {
        val incoming = mutableListOf<Pair<String, ConversationType>>()
        override fun incoming(message: ChatMessage, conversationName: String, type: ConversationType, chatVisible: Boolean) {
            incoming += message.id to type
        }
    }

    private class FakeIdentityStore : IdentityStore {
        val expectedPeerId = "local-peer"
        private var name = ""
        private var phoneNumber = "+15551234567"

        override fun getPeerId() = expectedPeerId
        override fun getDisplayName() = name
        override fun saveDisplayName(name: String) {
            this.name = name
        }

        override fun getPhoneNumber() = phoneNumber

        override fun savePhoneNumber(phoneNumber: String) {
            this.phoneNumber = phoneNumber
        }
    }

    private class FakeChatStore : ChatStore {
        var closed = false
        var closeCalls = 0
        private val peers = linkedMapOf<String, String>()
        private val messages = linkedMapOf<String, ChatMessage>()
        private val groups = linkedMapOf<String, PrivateGroup>()
        private val contacts = linkedMapOf<String, SavedContact>()
        private val phoneHashes = linkedMapOf<String, String>()
        private val usernames = linkedMapOf<String, String>()
        private val deletions = mutableMapOf<String, Long>()
        private val hiddenChats = mutableSetOf<String>()
        private val deletedMessageIds = mutableSetOf<String>()

        override fun savePeer(peerId: String, name: String, phoneHash: String) {
            peers[peerId] = name
            phoneHashes[peerId] = phoneHash
        }

        override fun saveMeshPeer(peerId: String, name: String, phoneHash: String) {
            peers[peerId] = name
            phoneHashes[peerId] = phoneHash
        }

        override fun savePeerUsername(peerId: String, username: String) { usernames[peerId] = username }

        override fun saveContact(contact: SavedContact) {
            contacts[contact.id] = contact
        }

        override fun getSavedContacts(): List<SavedContact> = contacts.values.toList()

        override fun deleteContact(contactId: String) {
            contacts.entries.removeAll { it.value.id == contactId }
        }

        override fun linkContact(phoneHash: String, peerId: String) {
            val contact = contacts.values.firstOrNull { it.phoneHash == phoneHash } ?: return
            contacts[contact.id] = contact.copy(linkedPeerId = peerId)
        }

        override fun getKnownContacts(): List<GroupMember> = peers
            .filterKeys { it != MeshGroup.ID }
            .map { GroupMember(it.key, it.value, phoneHashes[it.key].orEmpty()) }

        override fun saveGroup(group: PrivateGroup) {
            groups[group.id] = group
        }

        override fun getGroups(): List<PrivateGroup> = groups.values.toList()

        override fun getCloudPendingGroups(): List<PrivateGroup> = groups.values.filterNot(PrivateGroup::cloudSynced)

        override fun markGroupCloudSynced(groupId: String, revision: Long) {
            val group = groups[groupId] ?: return
            if (group.createdAt == revision) groups[groupId] = group.copy(cloudSynced = true)
        }

        override fun isGroupMember(groupId: String, peerId: String, phoneHash: String): Boolean =
            groups[groupId]?.members?.any {
                it.peerId == peerId || (phoneHash.isNotBlank() && it.phoneHash == phoneHash)
            } == true

        override fun saveMessage(message: ChatMessage): Boolean {
            if (message.id in deletedMessageIds || message.sentAt <= (deletions[message.peerId] ?: Long.MIN_VALUE)) return false
            if (messages.containsKey(message.id)) return false
            messages[message.id] = message
            hiddenChats.remove(message.peerId)
            return true
        }

        override fun updateMessageStatus(messageId: String, status: MessageStatus) {
            val message = messages[messageId] ?: return
            if (message.status.ordinal < status.ordinal) messages[messageId] = message.copy(status = status)
        }

        override fun getConversations(): List<ConversationSummary> {
            val direct = peers.map { (peerId, name) ->
                val last = messages.values.filter { it.peerId == peerId }.maxByOrNull { it.sentAt }
                ConversationSummary(
                    peerId = peerId,
                    name = name,
                    lastMessage = last?.text.orEmpty(),
                    lastMessageAt = last?.sentAt ?: 0L,
                    username = usernames[peerId].orEmpty(),
                    type = if (peerId == MeshGroup.ID) ConversationType.OPEN_MESH else ConversationType.DIRECT,
                )
            }
            val privateGroups = groups.values.map { group ->
                val last = messages.values.filter { it.peerId == group.id }.maxByOrNull { it.sentAt }
                ConversationSummary(
                    peerId = group.id,
                    name = group.name,
                    lastMessage = last?.text.orEmpty(),
                    lastMessageAt = last?.sentAt ?: group.createdAt,
                    type = ConversationType.PRIVATE_GROUP,
                    memberCount = group.members.size,
                )
            }
            return (direct + privateGroups).filterNot { it.peerId in hiddenChats }
        }

        override fun getMessages(peerId: String): List<ChatMessage> =
            messages.values.filter { it.peerId == peerId }.sortedBy { it.sentAt }

        override fun getPendingMessages(peerId: String): List<ChatMessage> =
            getMessages(peerId).filter {
                it.author == MessageAuthor.ME && it.status != MessageStatus.DELIVERED
            }

        override fun getCloudPendingMessages(): List<ChatMessage> = messages.values.filter {
            it.author == MessageAuthor.ME && !it.cloudSynced
        }

        override fun markCloudSynced(messageId: String) {
            messages[messageId]?.let { messages[messageId] = it.copy(cloudSynced = true) }
        }

        override fun moveConversation(fromPeerId: String, toPeerId: String) {
            if (fromPeerId == toPeerId) return
            messages.replaceAll { _, message ->
                if (message.peerId == fromPeerId) message.copy(peerId = toPeerId) else message
            }
            peers.remove(fromPeerId)
            if (usernames[toPeerId].isNullOrBlank()) usernames[fromPeerId]?.let { usernames[toPeerId] = it }
            usernames.remove(fromPeerId)
        }

        override fun deleteConversation(peerId: String) {
            deletedMessageIds += getMessages(peerId).map { it.id }
            messages.entries.removeAll { it.value.peerId == peerId }
            deletions[peerId] = System.currentTimeMillis()
            hiddenChats += peerId
        }

        override fun reopenConversation(peerId: String) { hiddenChats.remove(peerId) }

        override fun close() { closed = true; closeCalls++ }
    }

    private class FakeNearbyChatController : NearbyChatController {
        override var listener: NearbyTransport.Listener? = null
        var closed = false
        var closeCalls = 0
        var advertisingStarted = false
        var advertisedName: String? = null
        var advertisedPeerId: String? = null
        var discoveryStarted = false
        val sentMessages = mutableListOf<OutgoingNearbyMessage>()
        val acknowledgements = mutableListOf<Pair<String, String>>()
        val groupSynchronizations = mutableListOf<Pair<String, List<StoredGroupMessage>>>()
        val publishedGroups = mutableListOf<PrivateGroup>()
        var stopped = false

        override fun startAdvertising(displayName: String, peerId: String, phoneHash: String) {
            advertisingStarted = true
            advertisedName = displayName
            advertisedPeerId = peerId
        }

        override fun startDiscovery() {
            discoveryStarted = true
        }

        override fun connectToDevice(endpointId: String) = Unit
        val accepted = mutableListOf<String>()
        val knownAccepted = mutableListOf<Pair<String, TrustedNearbyIdentity>>()
        var identityProofAvailable = false
        override fun canVerifyIdentity(endpointId: String) = identityProofAvailable
        override fun acceptKnownConnection(endpointId: String, identity: TrustedNearbyIdentity) { knownAccepted += endpointId to identity }
        val rejected = mutableListOf<String>()
        override fun acceptConnection(endpointId: String) { accepted += endpointId }
        override fun rejectConnection(endpointId: String) { rejected += endpointId }
        override fun sendMessage(message: OutgoingNearbyMessage) {
            sentMessages += message
        }

        override fun publishGroup(group: PrivateGroup) {
            publishedGroups += group
        }

        override fun synchronizeGroups(
            peerId: String,
            groups: List<PrivateGroup>,
            messages: List<StoredGroupMessage>,
        ) {
            groupSynchronizations += peerId to messages
        }

        override fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String) {
            acknowledgements += senderId to messageId
        }
        override fun disconnect(peerId: String) = Unit
        override fun stop() {
            stopped = true
        }
        override fun close() { closed = true; closeCalls++ }
    }

    private class FakeAuthRepository(override var account: AuthAccount = AuthAccount()) : AuthRepository {
        override fun ensureGuestSession(onComplete: () -> Unit) = onComplete()
        override fun createEmailAccount(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
            account = AuthAccount(uid = "new-account", email = email, hasPassword = true)
            onSuccess()
        }
        override fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) = onSuccess()
        override fun signInWithGoogleToken(token: String, onSuccess: () -> Unit, onError: (String) -> Unit) = onSuccess()
        override fun sendVerificationEmail(onComplete: (Boolean) -> Unit) = onComplete(true)
        override fun refreshAccount(onComplete: () -> Unit) = onComplete()
        override fun ensureSignedIn(onComplete: (Boolean) -> Unit) = onComplete(account.uid.isNotBlank())
        override fun signOut() { account = AuthAccount() }
    }

    private class FakeAccountProfiles : AccountProfileRepository {
        override fun validate(username: String, displayName: String): String? = null
        override suspend fun load(): PublicAccountProfile? = null
        override suspend fun claim(username: String, displayName: String) = PublicAccountProfile(username, displayName)
    }

    private class FakeCloudChatController : CloudChatController {
        var listener: CloudChatController.Listener? = null
        var startedAccountId: String? = null
        var failAccountFetch = false
        var failKeyPublication = false
        var beforeLookup: (suspend () -> Unit)? = null
        var beforeAccountFetch: (suspend () -> Unit)? = null
        val accountFetches = mutableListOf<String>()
        val directMessages = mutableListOf<Pair<String, CloudChatMessage>>()
        val groups = mutableListOf<CloudPrivateGroup>()
        val groupMessages = mutableListOf<Pair<String, CloudChatMessage>>()
        private val accounts = mutableMapOf<String, CloudAccount>()
        private val phones = mutableMapOf<String, MutableSet<String>>()
        private val emails = mutableMapOf<String, String>()

        fun addAccount(uid: String, name: String, peerId: String, phone: String = "", email: String = "",
                       username: String = uid.replace('-', '_'), nearbyPublicKey: String = "") {
            accounts[uid] = CloudAccount(uid, name, peerId, username, nearbyPublicKey)
            if (phone.isNotBlank()) phones.getOrPut(phone) { mutableSetOf() }.add(uid)
            if (email.isNotBlank()) emails[email.lowercase()] = uid
        }

        override fun start(accountUid: String, listener: CloudChatController.Listener) {
            startedAccountId = accountUid
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        override suspend fun publishAccount(profile: ContactProfile, peerId: String) = Unit
        var keyPublications = 0
        override suspend fun publishNearbyKey(peerId: String, publicKey: String) {
            check(!failKeyPublication) { "Device-key publication unavailable" }
            keyPublications++
        }

        override suspend fun getAccount(uid: String): CloudAccount? {
            accountFetches += uid
            beforeAccountFetch?.invoke()
            return if (failAccountFetch) error("Firestore profile fetch unavailable") else accounts[uid]
        }

        override suspend fun findAccounts(phoneNumber: String, email: String, peerId: String,
                                          username: String): List<CloudAccount> {
            beforeLookup?.invoke()
            return accounts.values.filter { account ->
                (phoneNumber.isNotBlank() && account.uid in phones[phoneNumber].orEmpty()) ||
                    (email.isNotBlank() && emails[email.lowercase()] == account.uid) ||
                    (peerId.isNotBlank() && account.peerId == peerId) ||
                    (username.isNotBlank() && account.username == username)
            }
        }

        override suspend fun sendDirect(otherUid: String, message: CloudChatMessage) {
            directMessages += otherUid to message
        }

        override suspend fun saveGroup(group: CloudPrivateGroup) {
            groups += group
        }

        override suspend fun deleteGroup(groupId: String) = Unit

        override suspend fun sendGroup(groupId: String, message: CloudChatMessage) {
            groupMessages += groupId to message
        }
    }
}
