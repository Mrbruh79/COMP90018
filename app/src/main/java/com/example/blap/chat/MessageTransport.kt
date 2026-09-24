package com.example.blap.chat

interface MessageTransport {
    fun sendMessage(message: OutgoingMessageEnvelope)
    fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String)
}

data class AcceptedIncomingMessage(
    val message: ChatMessage,
    val conversation: ConversationSummary,
)
