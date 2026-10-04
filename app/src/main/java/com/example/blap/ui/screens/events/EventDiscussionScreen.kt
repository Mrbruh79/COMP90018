package com.example.blap.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blap.event.EventDiscussionComment
import com.example.blap.event.EventUiState
import com.example.blap.ui.components.SubScreenHeader
import com.example.blap.ui.screens.events.formatEventTime

@Composable
internal fun EventDiscussionScreen(
    state: EventUiState,
    onCreateComment: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    val event = state.selectedEvent ?: return
    val writable = !event.isDeleted && System.currentTimeMillis() <= event.endsAt &&
        state.membership?.canParticipate == true
    val isAdmin = state.membership?.let { it.isAdmin && event.isAdmin(it.userId) } == true
    var draft by remember(event.id) { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Event discussion", onBack)
        Text(
            "Online comments for event members · replies can be nested",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (state.discussionRoots.isEmpty()) {
                item { Text("No comments yet. Start the discussion.") }
            }
            items(state.discussionRoots, key = { "discussion-root-${it.id}" }) { comment ->
                DiscussionCommentCard(
                    comment = comment,
                    liked = comment.id in state.likedDiscussionCommentIds,
                    writable = writable,
                    canDelete = isAdmin ||
                        (writable && comment.authorId == state.currentUserId),
                    adminDelete = isAdmin,
                    onLike = { onToggleLike(comment.id) },
                    onDelete = { onDeleteComment(comment.id) },
                    primaryActionLabel = "Open thread",
                    onPrimaryAction = { onOpenThread(comment.id) },
                )
            }
            if (state.discussionHasMoreRoots) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.loading) "Loading…" else "Load older comments") }
                }
            }
        }
        if (writable) {
            DiscussionComposer(
                text = draft,
                maximumLength = EventDiscussionComment.MAX_ROOT_BODY_LENGTH,
                placeholder = "Add a comment",
                enabled = !state.loading,
                onTextChanged = { draft = it },
                onSend = {
                    onCreateComment(it)
                    draft = ""
                },
            )
        } else {
            Text("This discussion is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun EventDiscussionThreadScreen(
    state: EventUiState,
    onReply: (String, String?) -> Unit,
    onToggleLike: (String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onBack: () -> Unit,
) {
    val event = state.selectedEvent ?: return
    val root = state.selectedDiscussionRoot
    val writable = !event.isDeleted && System.currentTimeMillis() <= event.endsAt &&
        state.membership?.canParticipate == true
    val isAdmin = state.membership?.let { it.isAdmin && event.isAdmin(it.userId) } == true
    var draft by remember(state.selectedDiscussionThreadId) { mutableStateOf("") }
    var replyTarget by remember(state.selectedDiscussionThreadId) { mutableStateOf<EventDiscussionComment?>(null) }
    val orderedReplies = remember(state.discussionReplies) { orderDiscussionReplies(state.discussionReplies) }
    val defaultReplyTarget = root?.takeUnless(EventDiscussionComment::isAuthorDeleted)
        ?: orderedReplies.firstOrNull {
            !it.isAuthorDeleted && it.depth < EventDiscussionComment.MAX_DISCUSSION_DEPTH
        }

    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Thread", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (root == null) {
                item { Text("This thread is no longer available.") }
            } else {
                item(key = "thread-root-${root.id}") {
                    DiscussionCommentCard(
                        comment = root,
                        liked = root.id in state.likedDiscussionCommentIds,
                        writable = writable,
                        canDelete = isAdmin ||
                            (writable && root.authorId == state.currentUserId),
                        adminDelete = isAdmin,
                        onLike = { onToggleLike(root.id) },
                        onDelete = { onDeleteComment(root.id) },
                        primaryActionLabel = if (root.depth < EventDiscussionComment.MAX_DISCUSSION_DEPTH) "Reply" else null,
                        onPrimaryAction = { replyTarget = root },
                    )
                }
                items(orderedReplies, key = { "thread-reply-${it.id}" }) { comment ->
                    DiscussionCommentCard(
                        comment = comment,
                        liked = comment.id in state.likedDiscussionCommentIds,
                        writable = writable,
                        canDelete = isAdmin ||
                            (writable && comment.authorId == state.currentUserId),
                        adminDelete = isAdmin,
                        onLike = { onToggleLike(comment.id) },
                        onDelete = { onDeleteComment(comment.id) },
                        primaryActionLabel = if (comment.depth < EventDiscussionComment.MAX_DISCUSSION_DEPTH) "Reply" else null,
                        onPrimaryAction = { replyTarget = comment },
                        modifier = Modifier.padding(start = (comment.depth.coerceAtMost(4) * 12).dp),
                    )
                }
            }
        }
        if (writable && defaultReplyTarget != null) {
            val target = replyTarget?.takeIf { candidate ->
                !candidate.isAuthorDeleted &&
                    (candidate.id == root?.id || state.discussionReplies.any { it.id == candidate.id })
            } ?: defaultReplyTarget
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Replying to ${target.authorName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (replyTarget != null) {
                    TextButton(onClick = { replyTarget = null }) { Text("Reply to thread") }
                }
            }
            DiscussionComposer(
                text = draft,
                maximumLength = EventDiscussionComment.MAX_REPLY_BODY_LENGTH,
                placeholder = "Write a reply",
                enabled = !state.loading,
                onTextChanged = { draft = it },
                onSend = {
                    onReply(it, target.id)
                    draft = ""
                    replyTarget = null
                },
            )
        } else if (!writable) {
            Text("This discussion is read-only.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("There are no comments available to reply to.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DiscussionCommentCard(
    comment: EventDiscussionComment,
    liked: Boolean,
    writable: Boolean,
    canDelete: Boolean,
    adminDelete: Boolean,
    onLike: () -> Unit,
    onDelete: () -> Unit,
    primaryActionLabel: String?,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by remember(comment.id) { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    when {
                        adminDelete && comment.isRoot -> "Delete this entire thread?"
                        adminDelete -> "Delete this reply branch?"
                        else -> "Delete your comment?"
                    },
                )
            },
            text = {
                Text(
                    if (adminDelete) {
                        "This permanently deletes the selected comment and all replies beneath it for every event member."
                    } else {
                        "Your text will be replaced with [deleted] so existing replies keep their context."
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(comment.authorName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                if (comment.isAuthorDeleted) "[deleted]" else comment.body,
                modifier = Modifier.padding(top = 5.dp),
                color = if (comment.isAuthorDeleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                formatEventTime(comment.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!comment.isAuthorDeleted) {
                    TextButton(onClick = onLike, enabled = writable) {
                        Text(if (liked) "Unlike · ${comment.likeCount}" else "Like · ${comment.likeCount}")
                    }
                }
                if (comment.isRoot) {
                    Text(
                        "💬 ${comment.replyCount}",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (primaryActionLabel != null &&
                    (!comment.isAuthorDeleted || primaryActionLabel == "Open thread")
                ) {
                    TextButton(onClick = onPrimaryAction, enabled = writable || primaryActionLabel == "Open thread") {
                        Text(primaryActionLabel)
                    }
                }
                if (canDelete && !comment.isAuthorDeleted) {
                    TextButton(onClick = { showDeleteConfirmation = true }) {
                        Text(
                            when {
                                adminDelete && comment.isRoot -> "Delete thread"
                                adminDelete -> "Delete branch"
                                else -> "Delete"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscussionComposer(
    text: String,
    maximumLength: Int,
    placeholder: String,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChanged(it.take(maximumLength)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            supportingText = { Text("${text.length} / $maximumLength") },
            minLines = 2,
            maxLines = 5,
            enabled = enabled,
        )
        Button(
            onClick = { onSend(text) },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Post") }
    }
}

private fun orderDiscussionReplies(replies: List<EventDiscussionComment>): List<EventDiscussionComment> {
    val byParent = replies.groupBy(EventDiscussionComment::parentId)
    val result = mutableListOf<EventDiscussionComment>()
    val visited = mutableSetOf<String>()

    fun appendChildren(parentId: String) {
        byParent[parentId].orEmpty().sortedBy(EventDiscussionComment::createdAt).forEach { reply ->
            if (visited.add(reply.id)) {
                result += reply
                appendChildren(reply.id)
            }
        }
    }

    replies.filter { reply -> replies.none { it.id == reply.parentId } }
        .sortedBy(EventDiscussionComment::createdAt)
        .forEach { reply ->
            if (visited.add(reply.id)) {
                result += reply
                appendChildren(reply.id)
            }
        }
    replies.filterNot { it.id in visited }.sortedBy(EventDiscussionComment::createdAt).forEach(result::add)
    return result
}
