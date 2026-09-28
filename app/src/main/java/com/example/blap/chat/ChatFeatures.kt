package com.example.blap.chat

import java.net.URLDecoder
import java.net.URLEncoder

sealed interface ChatContent {
    data class Text(val body: String) : ChatContent
    data class Reply(val body: String, val targetId: String, val excerpt: String) : ChatContent
    data class Poll(val question: String, val options: List<String>) : ChatContent
    data class Vote(val pollId: String, val option: Int) : ChatContent
    data class Edit(val targetId: String, val body: String) : ChatContent
    data class Delete(val targetId: String) : ChatContent
}

/** Structured chat actions travel in the existing message text field on cloud and mesh. */
object ChatFeatures {
    private const val PREFIX = "\u001fCG2|"

    fun encode(content: ChatContent): String = when (content) {
        is ChatContent.Text -> content.body
        is ChatContent.Reply -> pack("R", content.targetId, content.excerpt, content.body)
        is ChatContent.Poll -> pack("P", content.question, *content.options.toTypedArray())
        is ChatContent.Vote -> pack("V", content.pollId, content.option.toString())
        is ChatContent.Edit -> pack("E", content.targetId, content.body)
        is ChatContent.Delete -> pack("D", content.targetId)
    }

    fun decode(raw: String): ChatContent {
        if (!raw.startsWith(PREFIX)) return ChatContent.Text(raw)
        return runCatching {
            val parts = raw.removePrefix(PREFIX).split('|')
            val values = parts.drop(1).map { URLDecoder.decode(it, "UTF-8") }
            when (parts.firstOrNull()) {
                "R" -> if (values.size == 3 && values[0].isNotBlank() && values[2].isNotBlank())
                    ChatContent.Reply(values[2], values[0], values[1]) else null
                "P" -> if (values.size in 3..5 && values.all(String::isNotBlank))
                    ChatContent.Poll(values[0], values.drop(1)) else null
                "V" -> if (values.size == 2 && values[0].isNotBlank())
                    values[1].toIntOrNull()?.takeIf { it in 0..3 }?.let { ChatContent.Vote(values[0], it) }
                    else null
                "E" -> if (values.size == 2 && values.all(String::isNotBlank))
                    ChatContent.Edit(values[0], values[1]) else null
                "D" -> if (values.size == 1 && values[0].isNotBlank()) ChatContent.Delete(values[0]) else null
                else -> null
            }
        }.getOrNull() ?: ChatContent.Text(raw)
    }

    fun preview(raw: String): String = when (val content = decode(raw)) {
        is ChatContent.Text -> content.body
        is ChatContent.Reply -> content.body
        is ChatContent.Poll -> "Poll: ${content.question}"
        is ChatContent.Vote -> "Voted in a poll"
        is ChatContent.Edit -> "Edited a message"
        is ChatContent.Delete -> "Deleted a message"
    }

    private fun pack(type: String, vararg values: String): String =
        PREFIX + type + values.joinToString(separator = "|", prefix = "|") {
            URLEncoder.encode(it, "UTF-8")
        }
}

data class PresentedMessage(
    val message: ChatMessage,
    val content: ChatContent,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val votes: List<Int> = emptyList(),
    val myVote: Int? = null,
)

object ChatTimeline {
    fun present(messages: List<ChatMessage>): List<PresentedMessage> {
        val ordered = messages.sortedWith(compareBy(ChatMessage::sentAt, ChatMessage::id))
        val originals = ordered.mapNotNull { message ->
            when (val content = ChatFeatures.decode(message.text)) {
                is ChatContent.Text, is ChatContent.Reply, is ChatContent.Poll ->
                    PresentedMessage(message, content)
                else -> null
            }
        }.associateByTo(linkedMapOf(), { it.message.id }, { it })
        val votes = mutableMapOf<String, MutableMap<String, Pair<Int, Boolean>>>()

        ordered.forEach { action ->
            when (val content = ChatFeatures.decode(action.text)) {
                is ChatContent.Edit -> {
                    val original = originals[content.targetId] ?: return@forEach
                    if (!original.deleted && sameSender(original.message, action) &&
                        (original.content is ChatContent.Text || original.content is ChatContent.Reply)) {
                        val changed = when (val previous = original.content) {
                            is ChatContent.Reply -> previous.copy(body = content.body)
                            else -> ChatContent.Text(content.body)
                        }
                        originals[content.targetId] = original.copy(content = changed, edited = true)
                    }
                }
                is ChatContent.Delete -> {
                    val original = originals[content.targetId] ?: return@forEach
                    if (sameSender(original.message, action)) {
                        originals[content.targetId] = original.copy(deleted = true)
                    }
                }
                is ChatContent.Vote -> {
                    val poll = originals[content.pollId] ?: return@forEach
                    if (poll.deleted) return@forEach
                    val options = (poll.content as? ChatContent.Poll)?.options ?: return@forEach
                    val sender = action.senderId.ifBlank { action.senderAccountId }
                    if (sender.isNotBlank() && content.option in options.indices) {
                        votes.getOrPut(content.pollId) { mutableMapOf() }[sender] =
                            content.option to (action.author == MessageAuthor.ME)
                    }
                }
                else -> Unit
            }
        }
        return originals.values.map { rawItem ->
            val reply = rawItem.content as? ChatContent.Reply
            val item = if (reply != null && originals[reply.targetId]?.deleted == true)
                rawItem.copy(content = reply.copy(excerpt = "Deleted message")) else rawItem
            val poll = item.content as? ChatContent.Poll
            if (poll == null) item else {
                val choices = votes[item.message.id].orEmpty().values
                item.copy(
                    votes = poll.options.indices.map { index -> choices.count { it.first == index } },
                    myVote = choices.firstOrNull { it.second }?.first,
                )
            }
        }
    }

    private fun sameSender(first: ChatMessage, second: ChatMessage): Boolean =
        (first.senderAccountId.isNotBlank() && first.senderAccountId == second.senderAccountId) ||
            (first.senderId.isNotBlank() && first.senderId == second.senderId)
}
