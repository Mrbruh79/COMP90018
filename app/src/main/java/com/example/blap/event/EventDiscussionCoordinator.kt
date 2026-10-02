package com.example.blap.event

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns event-discussion navigation, observers and write operations.
 *
 * [EventCoordinator] remains the screen-level facade, while this class keeps discussion-specific
 * observer lifetimes and rules out of the main event orchestration path.
 */
internal class EventDiscussionCoordinator(
    private val remoteRepository: EventRemoteRepository,
    private val scope: CoroutineScope,
    private val currentState: () -> EventUiState,
    private val updateState: (((EventUiState) -> EventUiState) -> Unit),
    private val clock: () -> Long,
) {
    private var rootsObserver: AutoCloseable? = null
    private var threadObserver: AutoCloseable? = null
    private var likesObserver: AutoCloseable? = null
    private var rootLimit = DISCUSSION_PAGE_SIZE

    fun show() {
        val state = currentState()
        val event = state.selectedEvent ?: return
        if (state.membership?.canParticipate != true || state.currentUserId !in event.memberIds) {
            updateState { it.copy(error = "Join this event before opening its discussion.") }
            return
        }
        closeThread()
        rootLimit = DISCUSSION_PAGE_SIZE
        updateState {
            it.copy(
                page = EventPage.DISCUSSION,
                discussionReplies = emptyList(),
                selectedDiscussionThreadId = null,
                error = null,
            )
        }
        observeRoots(event.id)
        observeLikes(event.id)
    }

    fun loadMoreRoots() {
        val state = currentState()
        val event = state.selectedEvent ?: return
        if (state.loading || !state.discussionHasMoreRoots) return
        rootLimit += DISCUSSION_PAGE_SIZE
        updateState { it.copy(loading = true, error = null) }
        observeRoots(event.id)
    }

    fun openThread(threadId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        if (state.discussionRoots.none { it.id == threadId }) return
        closeThread()
        updateState {
            it.copy(
                page = EventPage.DISCUSSION_THREAD,
                selectedDiscussionThreadId = threadId,
                discussionReplies = emptyList(),
                error = null,
            )
        }
        threadObserver = remoteRepository.observeDiscussionThread(
            eventId = event.id,
            threadId = threadId,
            onComments = { comments ->
                updateState { current ->
                    if (current.selectedEventId != event.id || current.selectedDiscussionThreadId != threadId) {
                        current
                    } else if (comments.isEmpty()) {
                        current.copy(
                            page = EventPage.DISCUSSION,
                            discussionRoots = current.discussionRoots.filterNot { it.id == threadId },
                            discussionReplies = emptyList(),
                            selectedDiscussionThreadId = null,
                            notice = "This discussion thread was removed by an admin.",
                        )
                    } else {
                        val root = comments.first()
                        current.copy(
                            discussionRoots = current.discussionRoots.map { existing ->
                                if (existing.id == root.id) root else existing
                            },
                            discussionReplies = comments.drop(1),
                        )
                    }
                }
            },
            onError = { failure ->
                updateState { it.copy(error = failure.readableEventMessage("Discussion thread could not be updated")) }
            },
        )
    }

    fun createComment(text: String, parentId: String? = null) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventDiscussionPolicy.writeError(event, membership, clock())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }

        val parent = parentId?.let { id ->
            (listOfNotNull(state.selectedDiscussionRoot) + state.discussionReplies)
                .firstOrNull { it.id == id }
        }
        val result = EventDiscussionPolicy.createComment(
            event = event,
            membership = membership,
            text = text,
            parentId = parentId,
            parent = parent,
            createdAt = clock(),
        )
        val comment = result.getOrElse { failure ->
            updateState { it.copy(error = failure.message ?: "Comment could not be created.") }
            return
        }

        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching { remoteRepository.createDiscussionComment(comment) }
                .onSuccess {
                    updateState {
                        it.copy(
                            loading = false,
                            notice = if (comment.isRoot) "Comment posted." else "Reply posted.",
                        )
                    }
                }
                .onFailure { failure ->
                    updateState {
                        it.copy(loading = false, error = failure.readableEventMessage("Comment could not be posted"))
                    }
                }
        }
    }

    fun toggleLike(commentId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        EventDiscussionPolicy.writeError(event, membership, clock())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val comment = (state.discussionRoots + state.discussionReplies).firstOrNull { it.id == commentId }
            ?: return
        val shouldLike = commentId !in state.likedDiscussionCommentIds
        scope.launch {
            runCatching { remoteRepository.setDiscussionLike(comment, shouldLike) }
                .onFailure { failure ->
                    updateState { it.copy(error = failure.readableEventMessage("Like could not be updated")) }
                }
        }
    }

    fun deleteComment(commentId: String) {
        val state = currentState()
        val event = state.selectedEvent ?: return
        val membership = state.membership ?: return
        val comment = (state.discussionRoots + state.discussionReplies).firstOrNull { it.id == commentId }
            ?: return
        EventDiscussionPolicy.deleteError(event, membership, comment, clock())?.let { error ->
            updateState { it.copy(error = error) }
            return
        }
        val adminRemoval = membership.isAdmin && event.isAdmin(membership.userId)
        updateState { it.copy(loading = true, error = null) }
        scope.launch {
            runCatching {
                if (adminRemoval) {
                    remoteRepository.deleteDiscussionBranchAsAdmin(comment, clock())
                } else {
                    remoteRepository.deleteOwnDiscussionComment(comment, clock())
                }
            }.onSuccess {
                updateState {
                    it.copy(
                        loading = false,
                        notice = when {
                            adminRemoval && comment.isRoot -> "Discussion thread deleted."
                            adminRemoval -> "Reply branch deleted."
                            else -> "Comment deleted."
                        },
                    )
                }
            }.onFailure { failure ->
                updateState {
                    it.copy(loading = false, error = failure.readableEventMessage("Comment could not be deleted"))
                }
            }
        }
    }

    fun backToList() {
        closeThread()
        updateState {
            it.copy(
                page = EventPage.DISCUSSION,
                discussionReplies = emptyList(),
                selectedDiscussionThreadId = null,
                error = null,
            )
        }
    }

    fun closeObservers() {
        rootsObserver?.close()
        closeThread()
        likesObserver?.close()
        rootsObserver = null
        likesObserver = null
    }

    private fun observeRoots(eventId: String) {
        rootsObserver?.close()
        val observedLimit = rootLimit
        rootsObserver = remoteRepository.observeDiscussionRoots(
            eventId = eventId,
            limit = observedLimit,
            onComments = { comments ->
                updateState { state ->
                    if (state.selectedEventId != eventId) state else state.copy(
                        discussionRoots = comments,
                        discussionHasMoreRoots = comments.size == observedLimit,
                        loading = false,
                    )
                }
            },
            onError = { failure ->
                updateState {
                    it.copy(loading = false, error = failure.readableEventMessage("Discussion could not be updated"))
                }
            },
        )
    }

    private fun observeLikes(eventId: String) {
        likesObserver?.close()
        likesObserver = remoteRepository.observeDiscussionLikes(
            eventId = eventId,
            onLikedCommentIds = { ids ->
                updateState { state ->
                    if (state.selectedEventId == eventId) state.copy(likedDiscussionCommentIds = ids) else state
                }
            },
            onError = { failure ->
                updateState { it.copy(notice = failure.readableEventMessage("Likes will refresh when online")) }
            },
        )
    }

    private fun closeThread() {
        threadObserver?.close()
        threadObserver = null
    }

    private companion object {
        const val DISCUSSION_PAGE_SIZE = 20
    }
}

internal object EventDiscussionPolicy {
    fun writeError(event: CommunityEvent, membership: EventMembership, now: Long): String? = when {
        event.isDeleted -> "Deleted events are read-only."
        !membership.canParticipate || membership.userId !in event.memberIds ->
            "Only active event members can participate in the discussion."
        now > event.endsAt -> "This discussion is read-only because the event has ended."
        else -> null
    }

    fun deleteError(
        event: CommunityEvent,
        membership: EventMembership,
        comment: EventDiscussionComment,
        now: Long,
    ): String? {
        if (now > event.endsAt && !membership.isAdmin) {
            return "This discussion is read-only because the event has ended."
        }
        val adminRemoval = membership.isAdmin && event.isAdmin(membership.userId)
        return if (!adminRemoval && comment.authorId != membership.userId) {
            "You can only delete your own comments."
        } else null
    }

    fun createComment(
        event: CommunityEvent,
        membership: EventMembership,
        text: String,
        parentId: String?,
        parent: EventDiscussionComment?,
        createdAt: Long,
        id: String = UUID.randomUUID().toString(),
    ): Result<EventDiscussionComment> = runCatching {
        require(parentId == null || parent != null) { "That comment is no longer available." }
        require(parent == null || parent.eventId == event.id) { "That comment belongs to another event." }
        require(parent == null || parent.depth < EventDiscussionComment.MAX_DISCUSSION_DEPTH) {
            "This reply chain has reached its nesting limit."
        }
        val cleanText = text.trim()
        val maximumLength = if (parent == null) {
            EventDiscussionComment.MAX_ROOT_BODY_LENGTH
        } else EventDiscussionComment.MAX_REPLY_BODY_LENGTH
        require(cleanText.isNotBlank() && cleanText.length <= maximumLength) {
            "Enter between 1 and $maximumLength characters."
        }
        EventDiscussionComment(
            id = id,
            eventId = event.id,
            threadId = parent?.threadId ?: id,
            parentId = parent?.id,
            ancestorIds = parent?.let { it.ancestorIds + it.id }.orEmpty(),
            depth = (parent?.depth ?: -1) + 1,
            authorId = membership.userId,
            authorName = membership.displayName.trim().take(24).ifBlank { "Event member" },
            body = cleanText,
            createdAt = createdAt,
        )
    }
}
