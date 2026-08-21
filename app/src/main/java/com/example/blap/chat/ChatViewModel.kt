package com.example.blap.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatViewModel(
    private val nearbyChatController: NearbyChatController,
) : ViewModel(), NearbyChatController.Listener {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        nearbyChatController.listener = this
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name.take(MAX_NAME_LENGTH))
    }

    fun startChat() {
        val name = _uiState.value.displayName.trim()
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter a display name first.")
            return
        }

        _uiState.value = _uiState.value.copy(
            displayName = name,
            connectionState = ChatConnectionState.DISCOVERING,
            discoveredDevices = emptyList(),
            connectedDevice = null,
            authenticationDigits = null,
            messages = emptyList(),
            error = null,
        )
        nearbyChatController.startAdvertising(name)
        nearbyChatController.startDiscovery()
    }

    fun connectToDevice(endpointId: String) {
        val device = _uiState.value.discoveredDevices.firstOrNull {
            it.endpointId == endpointId
        } ?: return

        _uiState.value = _uiState.value.copy(
            connectionState = ChatConnectionState.CONNECTING,
            connectedDevice = device,
            error = null,
        )
        nearbyChatController.connectToDevice(endpointId)
    }

    fun sendMessage(message: String) {
        val cleanMessage = message.trim().take(MAX_MESSAGE_LENGTH)
        if (cleanMessage.isBlank() || _uiState.value.connectionState != ChatConnectionState.CONNECTED) {
            return
        }
        nearbyChatController.sendMessage(cleanMessage)
    }

    fun disconnect() {
        nearbyChatController.disconnect()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onDeviceFound(device: NearbyDevice) {
        val devices = _uiState.value.discoveredDevices
            .filterNot { it.endpointId == device.endpointId }
            .plus(device)
            .sortedBy { it.name.lowercase() }
        _uiState.value = _uiState.value.copy(discoveredDevices = devices)
    }

    override fun onDeviceLost(endpointId: String) {
        _uiState.value = _uiState.value.copy(
            discoveredDevices = _uiState.value.discoveredDevices.filterNot {
                it.endpointId == endpointId
            },
        )
    }

    override fun onConnectionInitiated(
        device: NearbyDevice,
        authenticationDigits: String,
    ) {
        _uiState.value = _uiState.value.copy(
            connectionState = ChatConnectionState.CONNECTING,
            connectedDevice = device,
            authenticationDigits = authenticationDigits,
            error = null,
        )
    }

    override fun onConnected(device: NearbyDevice) {
        _uiState.value = _uiState.value.copy(
            connectionState = ChatConnectionState.CONNECTED,
            connectedDevice = device,
            discoveredDevices = emptyList(),
            authenticationDigits = null,
            error = null,
        )
    }

    override fun onMessageReceived(message: String) {
        if (message.isBlank()) return
        appendMessage(message, MessageAuthor.PEER)
    }

    override fun onMessageSent(message: String) {
        appendMessage(message, MessageAuthor.ME)
    }

    override fun onDisconnected() {
        _uiState.value = _uiState.value.copy(
            connectionState = ChatConnectionState.DISCONNECTED,
            connectedDevice = null,
            authenticationDigits = null,
        )
    }

    override fun onError(message: String) {
        val currentState = _uiState.value.connectionState
        _uiState.value = _uiState.value.copy(
            connectionState = if (currentState == ChatConnectionState.CONNECTED) {
                currentState
            } else {
                ChatConnectionState.ERROR
            },
            authenticationDigits = null,
            error = message,
        )
    }

    override fun onCleared() {
        nearbyChatController.close()
    }

    private fun appendMessage(message: String, author: MessageAuthor) {
        val chatMessage = ChatMessage(
            id = System.nanoTime(),
            text = message,
            author = author,
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + chatMessage,
        )
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        const val MAX_MESSAGE_LENGTH = 1_000

        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(NearbyChatManager(context.applicationContext)) as T
                }
            }
    }
}
