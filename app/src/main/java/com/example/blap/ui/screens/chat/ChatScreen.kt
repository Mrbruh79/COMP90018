package com.example.blap.ui.screens.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatContent
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatTimeline
import com.example.blap.chat.ChatViewModel
import com.example.blap.chat.ConversationSummary
import com.example.blap.chat.ConversationType
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.VoiceNoteRecorder
import com.example.blap.ui.components.Avatar
import com.example.blap.ui.components.DeleteConfirmationDialog
import com.example.blap.ui.components.MessageBubble
import com.example.blap.ui.components.MessageComposer
import com.example.blap.ui.screens.messages.reachable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ChatScreen(
    conversation: ConversationSummary,
    messages: List<ChatMessage>,
    directConnectionCount: Int,
    onSend: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onCreatePoll: (String, List<String>) -> Unit,
    onVote: (String, Int) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteChat: () -> Unit,
    onSendVoice: (Int, ByteArray) -> Unit,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    onOpenContactProfile: () -> Unit,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenGroupSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val presented = remember(messages) { ChatTimeline.present(messages) }
    var replyingTo by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var editingText by rememberSaveable(conversation.peerId) { mutableStateOf("") }
    var deletingId by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var showPollDialog by rememberSaveable(conversation.peerId) { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var confirmingChatDelete by rememberSaveable(conversation.peerId) { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val recorder = remember { VoiceNoteRecorder(context) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(conversation.peerId) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(messages.lastOrNull()?.id, imeBottom) {
        if (messages.isNotEmpty() &&
            (listState.firstVisibleItemIndex < 2 || messages.last().author == MessageAuthor.ME)
        ) listState.scrollToItem(0)
    }
    DisposableEffect(conversation.peerId) {
        onDispose { recorder.cancel() }
    }
    val finishRecording: (Boolean) -> Unit = { send ->
        recording = false
        val note = if (send) recorder.stop() else {
            recorder.cancel()
            null
        }
        note?.let { onSendVoice(it.durationMs, it.audio) }
    }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while (isActive) {
            elapsedMs = (SystemClock.elapsedRealtime() - started).toInt()
            if (elapsedMs >= ChatViewModel.MAX_VOICE_DURATION_MS) {
                finishRecording(true)
                break
            }
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Box(Modifier.clickable(enabled = conversation.type == ConversationType.DIRECT) {
                onOpenContactProfile()
            }) { Avatar(conversation.name, conversation.reachable()) }
            Column(
                Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .clickable(enabled = conversation.type == ConversationType.DIRECT) { onOpenContactProfile() },
            ) {
                Text(conversation.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.type == ConversationType.DIRECT) {
                    Text("Tap to view profile", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    when {
                        conversation.type == ConversationType.PRIVATE_GROUP && directConnectionCount > 0 ->
                            "${conversation.memberCount} members · relaying through mesh"
                        conversation.type == ConversationType.PRIVATE_GROUP ->
                            "${conversation.memberCount} members · no nearby links"
                        conversation.connected -> "Connected nearby"
                        conversation.onlineAccountLinked -> "Online account linked · not nearby"
                        else -> "Not connected nearby · messages may wait"
                    },
                    color = if (conversation.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { moreExpanded = true }) { Text("More") }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(text = { Text("Delete chat", color = MaterialTheme.colorScheme.error) }, onClick = {
                        moreExpanded = false
                        confirmingChatDelete = true
                    })
                    if (conversation.type == ConversationType.DIRECT) {
                        DropdownMenuItem(text = { Text("Contact profile") }, onClick = {
                            moreExpanded = false
                            onOpenContactProfile()
                        })
                    }
                    if (conversation.type == ConversationType.PRIVATE_GROUP) {
                        DropdownMenuItem(text = { Text("Group settings") }, onClick = {
                            moreExpanded = false
                            onOpenGroupSettings()
                        })
                    }
                    if (conversation.connected && conversation.type == ConversationType.DIRECT) {
                        DropdownMenuItem(text = { Text("Disconnect nearby") }, onClick = {
                            moreExpanded = false
                            onDisconnect()
                        })
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            if (presented.isEmpty()) {
                item { Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(presented.asReversed(), key = { it.message.id }) { item ->
                    MessageBubble(
                        item = item,
                        showSender = conversation.type != ConversationType.DIRECT,
                        onReply = { replyingTo = item.message.id; editingId = null },
                        onEdit = {
                            editingId = item.message.id
                            editingText = when (val content = item.content) {
                                is ChatContent.Text -> content.body
                                is ChatContent.Reply -> content.body
                                else -> ""
                            }
                            replyingTo = null
                        },
                        onDelete = { deletingId = item.message.id },
                        onVote = { option -> onVote(item.message.id, option) },
                    )
                }
            }
        }
        AnimatedVisibility(replyingTo != null || editingId != null) {
            val target = presented.firstOrNull { it.message.id == (editingId ?: replyingTo) }
            Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (editingId != null) "Editing message" else "Replying to ${target?.message?.senderName.orEmpty()}",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { replyingTo = null; editingId = null }) { Text("Cancel") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Text(
                    "Recording ${elapsedMs / 1000}s",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { finishRecording(false) }) { Text("Cancel") }
                TextButton(onClick = { finishRecording(true) }) { Text("Send") }
            } else {
                IconButton(onClick = {
                    if (!microphonePermissionGranted) {
                        onRequestMicrophonePermission()
                        return@IconButton
                    }
                    if (recorder.start()) {
                        elapsedMs = 0
                        recording = true
                    }
                }) { 
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Mic",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = { showPollDialog = true }) { Text("Poll") }
                Box(Modifier.weight(1f)) {
                    MessageComposer(
                        text = if (editingId != null) editingText else draft,
                        onTextChanged = { if (editingId != null) editingText = it else onDraftChanged(it) },
                        onSend = { text ->
                            when {
                                editingId != null -> onEdit(requireNotNull(editingId), text)
                                replyingTo != null -> onReply(requireNotNull(replyingTo), text)
                                else -> onSend(text)
                            }
                            replyingTo = null
                            editingId = null
                        },
                        sendLabel = if (editingId != null) "Save" else "Send",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (deletingId != null) DeleteConfirmationDialog(
        title = "Delete this message?",
        message = "It will disappear for people using the updated app when this change syncs.",
        onConfirm = { onDelete(requireNotNull(deletingId)); deletingId = null },
        onDismiss = { deletingId = null },
    )
    if (confirmingChatDelete) DeleteConfirmationDialog(
        title = "Delete this chat?",
        message = "Messages will be removed from this device. Your contact and the other person's history will stay. This does not leave a group.",
        onConfirm = { confirmingChatDelete = false; onDeleteChat() },
        onDismiss = { confirmingChatDelete = false },
    )
    if (showPollDialog) PollComposerDialog(
        onCreate = { question, options -> onCreatePoll(question, options); showPollDialog = false },
        onDismiss = { showPollDialog = false },
    )
}

@Composable
private fun PollComposerDialog(onCreate: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var third by rememberSaveable { mutableStateOf("") }
    var fourth by rememberSaveable { mutableStateOf("") }
    val options = listOf(first, second, third, fourth).map(String::trim).filter(String::isNotBlank)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(question, { question = it.take(180) }, label = { Text("Question") })
                OutlinedTextField(first, { first = it.take(80) }, label = { Text("Option 1") })
                OutlinedTextField(second, { second = it.take(80) }, label = { Text("Option 2") })
                OutlinedTextField(third, { third = it.take(80) }, label = { Text("Option 3 (optional)") })
                OutlinedTextField(fourth, { fourth = it.take(80) }, label = { Text("Option 4 (optional)") })
                Text("Tap an option in the chat to vote. You can change your vote.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(question.trim(), options) },
                enabled = question.isNotBlank() && first.isNotBlank() && second.isNotBlank() &&
                    options.size == options.distinct().size,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
