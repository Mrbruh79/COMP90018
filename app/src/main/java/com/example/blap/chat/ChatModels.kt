package com.example.blap.chat

enum class ChatConnectionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

data class NearbyDevice(
    val endpointId: String,
    val name: String,
)

enum class MessageAuthor {
    ME,
    PEER,
}

data class ChatMessage(
    val id: Long,
    val text: String,
    val author: MessageAuthor,
)

data class ChatUiState(
    val displayName: String = "",
    val connectionState: ChatConnectionState = ChatConnectionState.IDLE,
    val discoveredDevices: List<NearbyDevice> = emptyList(),
    val connectedDevice: NearbyDevice? = null,
    val authenticationDigits: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
)
