package com.example.blap.chat

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
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
        viewModel.openConversation(MeshGroup.ID)

        viewModel.sendMessage("Hello mesh")
        controller.listener?.onMessageReceived(
            IncomingNearbyMessage(
                messageId = "remote-group-message",
                conversationId = MeshGroup.ID,
                senderId = "carol-id",
                senderName = "Carol",
                senderPhoneHash = "",
                text = "Reached you through Bob",
                sentAt = 300L,
            ),
        )

        assertEquals(MeshGroup.ID, controller.sentMessages.single().peerId)
        val received = store.getMessages(MeshGroup.ID).last { it.id == "remote-group-message" }
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
                peerId = MeshGroup.ID,
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
        viewModel.openConversation(MeshGroup.ID)

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
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
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
        viewModel.openConversation(MeshGroup.ID)
        viewModel.updateMessageDraft("For everyone")
        viewModel.sendMessage("For everyone")
        assertEquals("For Bob", viewModel.uiState.value.messageDrafts["bob"])
        assertFalse(viewModel.uiState.value.messageDrafts.containsKey(MeshGroup.ID))
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
    fun changingContactPhoneRemovesTheOldPeerLink() {
        val store = FakeChatStore()
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller, store)
        viewModel.importDeviceContacts(listOf(DeviceContact("Bob", "+12025550198")))
        controller.listener?.onMeshPeerFound(GroupMember("bob", "Bob", requireNotNull(PhoneIdentity.hash("+12025550198"))))
        viewModel.openContact(viewModel.uiState.value.savedContacts.single().id)
        viewModel.updateContactPhone("+61400111222")
        viewModel.saveContact()
        assertEquals(null, store.getSavedContacts().single().linkedPeerId)
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
        viewModel.openConversation(MeshGroup.ID)
        viewModel.updateMessageDraft("Still writing")
        viewModel.stopChat()
        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
        assertEquals("Still writing", viewModel.uiState.value.messageDrafts[MeshGroup.ID])
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
    fun numberLookupStillWorksWhenSavedEmailHasNoOnlineMatch() {
        val store = FakeChatStore()
        val cloud = FakeCloudChatController()
        cloud.addAccount("bob-uid", "Bob", "bob-id", phone = "+12025550198")
        val viewModel = makeViewModel(FakeNearbyChatController(), store, cloud = cloud)
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
        assertTrue(viewModel.uiState.value.notice.orEmpty().contains("not verified"))
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

        assertEquals(1, store.getMessages("bob-id").size)
        assertEquals("Hi", store.getMessages("bob-id").single().text)
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
    fun multipleEmailOnlyContactsCanBeSavedAndSelectedForGroups() {
        val store = FakeChatStore()
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
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
        val viewModel = makeViewModel(FakeNearbyChatController(), store)
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

    private fun makeViewModel(
        controller: FakeNearbyChatController,
        store: FakeChatStore = FakeChatStore(),
        identityStore: FakeIdentityStore = FakeIdentityStore(),
        cloud: FakeCloudChatController? = null,
    ) = ChatViewModel(controller, store, identityStore, Dispatchers.Unconfined, cloudChatController = cloud)

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
        private val peers = linkedMapOf<String, String>()
        private val messages = linkedMapOf<String, ChatMessage>()
        private val groups = linkedMapOf<String, PrivateGroup>()
        private val contacts = linkedMapOf<String, SavedContact>()
        private val phoneHashes = linkedMapOf<String, String>()

        override fun savePeer(peerId: String, name: String, phoneHash: String) {
            peers[peerId] = name
            phoneHashes[peerId] = phoneHash
        }

        override fun saveMeshPeer(peerId: String, name: String, phoneHash: String) {
            peers[peerId] = name
            phoneHashes[peerId] = phoneHash
        }

        override fun saveContact(contact: SavedContact) {
            if (contact.phoneHash.isNotBlank()) {
                contacts.entries.removeAll { it.key != contact.id && it.value.phoneHash == contact.phoneHash }
            }
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
            if (messages.containsKey(message.id)) return false
            messages[message.id] = message
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
            return direct + privateGroups
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
        }

        override fun close() = Unit
    }

    private class FakeNearbyChatController : NearbyChatController {
        override var listener: NearbyChatController.Listener? = null
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
        override fun close() = Unit
    }

    private class FakeCloudChatController : CloudChatController {
        var listener: CloudChatController.Listener? = null
        val directMessages = mutableListOf<Pair<String, CloudChatMessage>>()
        val groups = mutableListOf<CloudPrivateGroup>()
        val groupMessages = mutableListOf<Pair<String, CloudChatMessage>>()
        private val accounts = mutableMapOf<String, CloudAccount>()
        private val phones = mutableMapOf<String, String>()
        private val emails = mutableMapOf<String, String>()

        fun addAccount(uid: String, name: String, peerId: String, phone: String = "", email: String = "") {
            accounts[uid] = CloudAccount(uid, name, peerId)
            if (phone.isNotBlank()) phones[phone] = uid
            if (email.isNotBlank()) emails[email.lowercase()] = uid
        }

        override fun start(accountUid: String, listener: CloudChatController.Listener) {
            this.listener = listener
        }

        override fun stop() {
            listener = null
        }

        override suspend fun publishAccount(profile: ContactProfile, peerId: String) = Unit

        override suspend fun getAccount(uid: String): CloudAccount? = accounts[uid]

        override suspend fun findAccounts(phoneNumber: String, email: String, peerId: String): List<CloudAccount> =
            accounts.values.filter { account ->
                (phoneNumber.isNotBlank() && phones[phoneNumber] == account.uid) ||
                    (email.isNotBlank() && emails[email.lowercase()] == account.uid) ||
                    (peerId.isNotBlank() && account.peerId == peerId)
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
