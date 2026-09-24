package com.example.blap.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
    @Test
    fun notificationRouteOpensConversationAfterReload() {
        val store = FakeChatStore().apply { savePeer("bob", "Bob") }
        val viewModel = makeViewModel(FakeNearbyChatController(), store)

        viewModel.openConversationFromNotification("bob")

        assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
        assertEquals("bob", viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun unknownNotificationRouteFallsBackToConversationList() {
        val viewModel = makeViewModel(FakeNearbyChatController())

        viewModel.openConversationFromNotification("missing")

        assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
        assertEquals(null, viewModel.uiState.value.selectedPeerId)
    }

    @Test
    fun notificationPermissionDenialShowsANotice() {
        val viewModel = makeViewModel(FakeNearbyChatController())
        viewModel.showError("Old error")

        viewModel.showNotice("Notifications are off.")

        assertEquals("Notifications are off.", viewModel.uiState.value.notice)
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun startChatNeedsAName() {
        val controller = FakeNearbyChatController()
        val viewModel = makeViewModel(controller)

        viewModel.startChat()

        assertFalse(controller.advertisingStarted)
        assertEquals("Enter a display name first.", viewModel.uiState.value.error)
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
            IncomingMessageEnvelope(
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
            IncomingMessageEnvelope("message-2", "bob-id", "bob-id", "Bob", "", "Made it", 200L),
        )

        assertEquals("Made it", store.getMessages("bob-id").single().text)
        assertEquals("bob-id" to "message-2", controller.acknowledgements.single())
    }

    @Test
    fun newIncomingMessageEmitsOneAcceptedEvent() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        val accepted = mutableListOf<AcceptedIncomingMessage>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.coordinator.acceptedIncomingMessages.take(1).toList(accepted)
        }

        controller.listener?.onMessageReceived(
            IncomingMessageEnvelope("m1", "bob", "bob", "Bob", "", "Hello", 100L),
        )

        assertEquals(listOf("m1"), accepted.map { it.message.id })
        job.cancel()
    }

    @Test
    fun duplicateIncomingMessageIsAcknowledgedButNotAcceptedTwice() {
        val controller = FakeNearbyChatController()
        val store = FakeChatStore()
        val viewModel = makeViewModel(controller, store)
        val accepted = mutableListOf<AcceptedIncomingMessage>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.coordinator.acceptedIncomingMessages.toList(accepted)
        }
        val message = IncomingMessageEnvelope("m1", "bob", "bob", "Bob", "", "Hello", 100L)

        controller.listener?.onMessageReceived(message)
        controller.listener?.onMessageReceived(message)

        assertEquals(1, store.getMessages("bob").size)
        assertEquals(1, accepted.size)
        assertEquals(2, controller.acknowledgements.size)
        job.cancel()
    }

    @Test
    fun acknowledgementFailureStillRefreshesAndEmitsNewMessageExactlyOnce() {
        val store = FakeChatStore()
        val coordinator = ChatCoordinator(
            chatStore = store,
            identityStore = FakeIdentityStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val accepted = mutableListOf<AcceptedIncomingMessage>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            coordinator.acceptedIncomingMessages.toList(accepted)
        }
        var acknowledgementAttempts = 0
        val throwingTransport = object : MessageTransport {
            override fun sendMessage(message: OutgoingMessageEnvelope) = Unit

            override fun acknowledgeMessage(
                conversationId: String,
                senderId: String,
                messageId: String,
            ) {
                acknowledgementAttempts++
                throw IllegalStateException("acknowledgement failed")
            }
        }
        val message = IncomingMessageEnvelope(
            "m1", "bob", "bob", "Bob", "", "Hello", 100L,
        )

        coordinator.receiveIncomingMessage(message, throwingTransport)
        coordinator.receiveIncomingMessage(message, throwingTransport)

        assertEquals(2, acknowledgementAttempts)
        assertEquals(listOf("m1"), store.getMessages("bob").map { it.id })
        assertEquals(listOf("m1"), accepted.map { it.message.id })
        assertEquals(
            "Hello",
            coordinator.uiState.value.conversations.first { it.peerId == "bob" }.lastMessage,
        )
        job.cancel()
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
            IncomingMessageEnvelope(
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
            IncomingMessageEnvelope(
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
            IncomingMessageEnvelope(
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

    private fun makeViewModel(
        controller: FakeNearbyChatController,
        store: FakeChatStore = FakeChatStore(),
        identityStore: FakeIdentityStore = FakeIdentityStore(),
    ): ChatViewModel {
        val coordinator = ChatCoordinator(
            chatStore = store,
            identityStore = identityStore,
            ioDispatcher = Dispatchers.Unconfined,
            initialNearbyChatController = controller,
        )
        return ChatViewModel(coordinator)
    }
}
