package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {
    @Test
    fun startChat_requiresDisplayName() {
        val controller = FakeNearbyChatController()
        val viewModel = ChatViewModel(controller)

        viewModel.startChat()

        assertFalse(controller.advertisingStarted)
        assertEquals(ChatConnectionState.IDLE, viewModel.uiState.value.connectionState)
        assertEquals("Enter a display name first.", viewModel.uiState.value.error)
    }

    @Test
    fun startChat_advertisesAndDiscovers() {
        val controller = FakeNearbyChatController()
        val viewModel = ChatViewModel(controller)
        viewModel.updateDisplayName("  Alice  ")

        viewModel.startChat()

        assertEquals("Alice", controller.advertisedName)
        assertTrue(controller.discoveryStarted)
        assertEquals(ChatConnectionState.DISCOVERING, viewModel.uiState.value.connectionState)
    }

    @Test
    fun devicesAreSortedAndRemovedByEndpoint() {
        val controller = FakeNearbyChatController()
        val viewModel = ChatViewModel(controller)

        controller.listener?.onDeviceFound(NearbyDevice("2", "Zara"))
        controller.listener?.onDeviceFound(NearbyDevice("1", "Bob"))
        assertEquals(listOf("Bob", "Zara"), viewModel.uiState.value.discoveredDevices.map { it.name })

        controller.listener?.onDeviceLost("1")
        assertEquals(listOf("Zara"), viewModel.uiState.value.discoveredDevices.map { it.name })
    }

    @Test
    fun sentAndReceivedMessagesAreAttributedCorrectly() {
        val controller = FakeNearbyChatController()
        val viewModel = ChatViewModel(controller)
        val bob = NearbyDevice("bob-id", "Bob")
        controller.listener?.onConnected(bob)

        viewModel.sendMessage("  Hello Bob  ")
        controller.listener?.onMessageSent(controller.lastMessage.orEmpty())
        controller.listener?.onMessageReceived("Hello Alice")

        val messages = viewModel.uiState.value.messages
        assertEquals(listOf("Hello Bob", "Hello Alice"), messages.map { it.text })
        assertEquals(listOf(MessageAuthor.ME, MessageAuthor.PEER), messages.map { it.author })
    }

    private class FakeNearbyChatController : NearbyChatController {
        override var listener: NearbyChatController.Listener? = null
        var advertisingStarted = false
        var advertisedName: String? = null
        var discoveryStarted = false
        var lastMessage: String? = null

        override fun startAdvertising(displayName: String) {
            advertisingStarted = true
            advertisedName = displayName
        }

        override fun startDiscovery() {
            discoveryStarted = true
        }

        override fun connectToDevice(endpointId: String) = Unit

        override fun sendMessage(message: String) {
            lastMessage = message
        }

        override fun disconnect() = Unit

        override fun close() = Unit
    }
}
