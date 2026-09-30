package com.example.blap.ui.components

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatContent
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.MessageStatus
import com.example.blap.chat.PresentedMessage
import com.example.blap.chat.VoiceNotePlayback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun MessageBubble(message: ChatMessage, showSender: Boolean) {
    MessageBubble(
        item = PresentedMessage(message, ChatContent.Text(message.text)),
        showSender = showSender,
        onReply = {},
        onEdit = {},
        onDelete = {},
        onVote = {},
        showActions = false,
    )
}

@Composable
internal fun MessageBubble(
    item: PresentedMessage,
    showSender: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onVote: (Int) -> Unit,
    showActions: Boolean = true,
) {
    val message = item.message
    val mine = message.author == MessageAuthor.ME
    var menuExpanded by remember { mutableStateOf(false) }
    var swipeOffset by remember(message.id) { mutableFloatStateOf(0f) }
    val visibleSwipeOffset by animateFloatAsState(swipeOffset, label = "Reply swipe")
    val replyThreshold = with(LocalDensity.current) { 68.dp.toPx() }
    val swipeLimit = with(LocalDensity.current) { 88.dp.toPx() }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine && showSender) {
            Text(
                message.senderName.ifBlank { "Mesh member" },
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box {
            if (showActions && !item.deleted) {
                Text(
                    "↩",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .offset { IntOffset(visibleSwipeOffset.roundToInt(), 0) }
                .pointerInput(message.id, showActions, item.deleted) {
                    if (showActions && !item.deleted) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                swipeOffset = (swipeOffset + amount).coerceIn(0f, swipeLimit)
                                change.consume()
                            },
                            onDragEnd = {
                                if (swipeOffset >= replyThreshold) onReply()
                                swipeOffset = 0f
                            },
                            onDragCancel = { swipeOffset = 0f },
                        )
                    }
                }
                .clip(RoundedCornerShape(17.dp))
                .border(
                    1.dp,
                    if (mine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(17.dp),
                )
                .background(
                    if (mine) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .clickable(enabled = showActions && !item.deleted) { menuExpanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val textColor = if (mine) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
                if (item.deleted) Text("Message deleted", color = textColor)
                else when (val content = item.content) {
                    is ChatContent.Text -> Text(content.body, color = textColor)
                    is ChatContent.Reply -> {
                        Text("Reply to: ${content.excerpt}", color = textColor,
                            style = MaterialTheme.typography.labelMedium)
                        Text(content.body, color = textColor)
                    }
                    is ChatContent.Poll -> {
                        Text(content.question, color = textColor, style = MaterialTheme.typography.titleMedium)
                        content.options.forEachIndexed { index, option ->
                            TextButton(onClick = { onVote(index) }) {
                                Text("${if (item.myVote == index) "✓ " else ""}$option · ${item.votes.getOrElse(index) { 0 }}")
                            }
                        }
                    }
                    is ChatContent.Voice -> VoiceMessageBubble(
                        messageId = message.id,
                        durationMs = content.durationMs,
                        audioBase64 = content.audioBase64,
                        textColor = textColor,
                    )
                    else -> Unit
                }
                if (item.edited && !item.deleted) Text("Edited", color = textColor,
                    style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = showActions && menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { menuExpanded = false; onReply() })
                if (mine) {
                    if (item.content is ChatContent.Text || item.content is ChatContent.Reply) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEdit() })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
        val timestamp = formatTimestamp(message.sentAt)
        if (mine) {
            val status = when (message.status) {
                MessageStatus.PENDING -> "Waiting"
                MessageStatus.SENT -> "Sent"
                MessageStatus.DELIVERED -> "Delivered"
            }
            Text(
                "$timestamp · $status",
                modifier = Modifier.padding(top = 3.dp, end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                timestamp,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(sentAt: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(sentAt))

@Composable
private fun VoiceMessageBubble(
    messageId: String,
    durationMs: Int,
    audioBase64: String,
    textColor: Color,
) {
    val context = LocalContext.current
    var playing by remember(messageId) { mutableStateOf(false) }
    val player = remember(messageId) { MediaPlayer() }
    val handler = remember(messageId) { Handler(Looper.getMainLooper()) }
    DisposableEffect(messageId, audioBase64) {
        val file = VoiceNotePlayback.writeCacheFile(context, messageId, audioBase64)
        val stopPlaying = Runnable { playing = false }
        if (file != null) {
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                player.setOnCompletionListener { handler.post(stopPlaying) }
            } catch (_: Exception) {
            }
        }
        onDispose {
            handler.removeCallbacks(stopPlaying)
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
    }
    val seconds = ((durationMs + 500) / 1000).coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            try {
                if (playing) {
                    player.pause()
                    player.seekTo(0)
                    playing = false
                } else {
                    player.start()
                    playing = true
                }
            } catch (_: Exception) {
                playing = false
            }
        }) { Text(if (playing) "Stop" else "Play", color = textColor) }
        Text("${seconds}s voice", color = textColor)
    }
}
