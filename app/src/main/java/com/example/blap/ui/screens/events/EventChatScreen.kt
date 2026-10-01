package com.example.blap.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.MessageStatus
import com.example.blap.event.EventUiState
import com.example.blap.ui.components.MessageBubble
import com.example.blap.ui.components.MessageComposer

@Composable
internal fun EventChatScreen(state: EventUiState, onSend: (String) -> Unit, onBack: () -> Unit) {
    var draft by remember(state.selectedEventId) { mutableStateOf("") }
    val membership = state.membership
    val writable = state.activeEventId == state.selectedEventId && state.selectedEvent?.isActive(System.currentTimeMillis()) == true
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("‹ Event") }
        Text("On-site chat", style = MaterialTheme.typography.headlineMedium)
        Text("Mesh only · not uploaded to the cloud", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (state.chatMessages.isEmpty()) item { Text("No on-site messages yet") }
            items(state.chatMessages.asReversed(), key = { it.id }) { message ->
                MessageBubble(
                    ChatMessage(
                        id = message.id,
                        peerId = message.eventId,
                        text = message.text,
                        author = if (message.senderId == membership?.userId) MessageAuthor.ME else MessageAuthor.PEER,
                        sentAt = message.createdAt,
                        status = MessageStatus.SENT,
                        senderId = message.senderId,
                        senderName = message.senderName,
                    ),
                    showSender = true,
                )
            }
        }
        if (writable) {
            MessageComposer(
                text = draft,
                onTextChanged = { draft = it },
                onSend = { onSend(it); draft = "" },
            )
        } else {
            Text("This saved chat is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}
