package com.example.blap.ui.screens.chat

data class ChatActions(
    val onStartChat: () -> Unit,
    val onStopChat: () -> Unit,
    val onConnect: (String) -> Unit,
    val onAcceptConnection: (String) -> Unit,
    val onRejectConnection: (String) -> Unit,
    val onOpenConversation: (String) -> Unit,
    val onBackToChats: () -> Unit,
    val onConversationSearchChanged: (String) -> Unit,
    val onMessageDraftChanged: (String) -> Unit,
    val onSendMessage: (String) -> Unit,
    val onSendReply: (String, String) -> Unit,
    val onSendVoice: (Int, ByteArray) -> Unit,
    val onEditMessage: (String, String) -> Unit,
    val onDeleteMessage: (String) -> Unit,
    val onDeleteChat: () -> Unit,
    val onCreatePoll: (String, List<String>) -> Unit,
    val onVoteInPoll: (String, Int) -> Unit,
    val onDisconnect: (String) -> Unit,
    val onDismissError: () -> Unit,
)
