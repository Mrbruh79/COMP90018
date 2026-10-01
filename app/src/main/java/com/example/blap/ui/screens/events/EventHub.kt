package com.example.blap.ui.screens.events

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventPage
import com.example.blap.event.EventUiState
import com.example.blap.ui.components.createQrBitmap

@Composable
fun EventHub(
    state: EventUiState,
    onBeginCreate: () -> Unit,
    onBeginEdit: () -> Unit,
    onCreate: (EventCreateRequest) -> Unit,
    onOpen: (String) -> Unit,
    onUpdate: (EventCreateRequest) -> Unit,
    onDeleteEvent: () -> Unit,
    onJoin: () -> Unit,
    onInvite: (String) -> Unit,
    onSearchParticipant: (String) -> Unit,
    onAcceptInvitation: (String) -> Unit,
    onDeclineInvitation: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onLeave: () -> Unit,
    onPromoteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteLocalData: () -> Unit,
    onShowAnnouncements: () -> Unit,
    onPublishAnnouncement: (String) -> Unit,
    onShowDiscussion: () -> Unit,
    onLoadMoreDiscussion: () -> Unit,
    onOpenDiscussionThread: (String) -> Unit,
    onCreateDiscussionComment: (String, String?) -> Unit,
    onToggleDiscussionLike: (String) -> Unit,
    onDeleteDiscussionComment: (String) -> Unit,
    onRequestGpsEntry: () -> Unit,
    onRequestAdminAccess: () -> Unit,
    onApproveAdminAccess: (String) -> Unit,
    onScanCheckInQr: () -> Unit,
    onShowCheckInQr: () -> Unit,
    onHideCheckInQr: () -> Unit,
    onShowSavedChat: () -> Unit,
    onSendChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    state.checkInQrPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = onHideCheckInQr,
            title = { Text("Venue check-in") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = createQrBitmap(payload, 700).asImageBitmap(),
                        contentDescription = "Static venue check-in QR",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "This signed QR can be displayed again or printed. It works only while the event " +
                            "is active, and attendees must still reach the event's Nearby mesh.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onHideCheckInQr) { Text("Close") } },
        )
    }

    when (state.page) {
        EventPage.LIST -> EventListScreen(
            state,
            onBeginCreate,
            onOpen,
            onAcceptInvitation,
            onDeclineInvitation,
        )
        EventPage.CREATE -> EventFormScreen(null, state.loading, onCreate, onBack)
        EventPage.EDIT -> EventFormScreen(state.selectedEvent, state.loading, onUpdate, onBack)
        EventPage.DETAIL -> EventDetailScreen(
            state,
            onBeginEdit,
            onDeleteEvent,
            onJoin,
            onInvite,
            onSearchParticipant,
            onRevokeInvitation,
            onLeave,
            onPromoteMember,
            onRemoveMember,
            onDeleteLocalData,
            onShowAnnouncements,
            onShowDiscussion,
            onRequestGpsEntry,
            onRequestAdminAccess,
            onApproveAdminAccess,
            onScanCheckInQr,
            onShowCheckInQr,
            onShowSavedChat,
            onBack,
        )
        EventPage.ANNOUNCEMENTS -> EventAnnouncementsScreen(state, onPublishAnnouncement, onBack)
        EventPage.ON_SITE_CHAT -> EventChatScreen(state, onSendChat, onBack)
        EventPage.DISCUSSION -> EventDiscussionScreen(
            state = state,
            onCreateComment = { text -> onCreateDiscussionComment(text, null) },
            onOpenThread = onOpenDiscussionThread,
            onToggleLike = onToggleDiscussionLike,
            onDeleteComment = onDeleteDiscussionComment,
            onLoadMore = onLoadMoreDiscussion,
            onBack = onBack,
        )
        EventPage.DISCUSSION_THREAD -> EventDiscussionThreadScreen(
            state = state,
            onReply = onCreateDiscussionComment,
            onToggleLike = onToggleDiscussionLike,
            onDeleteComment = onDeleteDiscussionComment,
            onBack = onBack,
        )
    }
}
