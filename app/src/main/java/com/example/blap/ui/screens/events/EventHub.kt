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
import com.example.blap.event.EventPage
import com.example.blap.event.EventUiState
import com.example.blap.location.GeoCoordinates
import com.example.blap.location.LocationFix
import com.example.blap.location.PlaceSearchResult
import com.example.blap.ui.components.createQrBitmap

@Composable
fun EventHub(
    state: EventUiState,
    actions: EventActions,
    getCurrentLocation: suspend () -> LocationFix?,
    searchPlaces: suspend (String) -> List<PlaceSearchResult>,
    addressForCoordinates: suspend (GeoCoordinates) -> String?,
) {
    state.checkInQrPayload?.let { payload ->
        AlertDialog(
            onDismissRequest = actions.onHideCheckInQr,
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
            confirmButton = { TextButton(onClick = actions.onHideCheckInQr) { Text("Close") } },
        )
    }

    when (state.page) {
        EventPage.LIST -> EventListScreen(
            state = state,
            onBeginCreate = actions.onBeginCreate,
            onSelectListSection = actions.onSelectListSection,
            onSearchQueryChanged = actions.onSearchQueryChanged,
            onSearchEvents = actions.onSearchEvents,
            onClearEventSearch = actions.onClearEventSearch,
            onDiscoverCity = actions.onDiscoverCity,
            onDiscoverNearby = actions.onDiscoverNearby,
            onDiscoveryDistanceChanged = actions.onDiscoveryDistanceChanged,
            onDateFilterChanged = actions.onDateFilterChanged,
            onAccessFilterChanged = actions.onAccessFilterChanged,
            onLoadMoreEvents = actions.onLoadMoreEvents,
            onOpen = actions.onOpen,
            onAcceptInvitation = actions.onAcceptInvitation,
            onDeclineInvitation = actions.onDeclineInvitation,
            getCurrentLocation = getCurrentLocation,
        )
        EventPage.CREATE -> EventFormScreen(
            null,
            state.loading,
            actions.onCreate,
            actions.onBack,
            getCurrentLocation,
            searchPlaces,
            addressForCoordinates,
        )
        EventPage.EDIT -> EventFormScreen(
            state.selectedEvent,
            state.loading,
            actions.onUpdate,
            actions.onBack,
            getCurrentLocation,
            searchPlaces,
            addressForCoordinates,
        )
        EventPage.DETAIL -> EventDetailScreen(
            state = state,
            onBeginEdit = actions.onBeginEdit,
            onDeleteEvent = actions.onDeleteEvent,
            onJoin = actions.onJoin,
            onInvite = actions.onInvite,
            onSearchParticipant = actions.onSearchParticipant,
            onRevokeInvitation = actions.onRevokeInvitation,
            onLeave = actions.onLeave,
            onPromoteMember = actions.onPromoteMember,
            onRemoveMember = actions.onRemoveMember,
            onShowAnnouncements = actions.onShowAnnouncements,
            onShowDiscussion = actions.onShowDiscussion,
            onRequestGpsEntry = actions.onRequestGpsEntry,
            onScanCheckInQr = actions.onScanCheckInQr,
            onShowCheckInQr = actions.onShowCheckInQr,
            onShowSavedChat = actions.onShowSavedChat,
            onBack = actions.onBack,
        )
        EventPage.ANNOUNCEMENTS -> EventAnnouncementsScreen(state, actions.onPublishAnnouncement, actions.onBack)
        EventPage.ON_SITE_CHAT -> EventChatScreen(state, actions.onSendChat, actions.onBack)
        EventPage.DISCUSSION -> EventDiscussionScreen(
            state = state,
            onCreateComment = { text -> actions.onCreateDiscussionComment(text, null) },
            onOpenThread = actions.onOpenDiscussionThread,
            onToggleLike = actions.onToggleDiscussionLike,
            onDeleteComment = actions.onDeleteDiscussionComment,
            onLoadMore = actions.onLoadMoreDiscussion,
            onBack = actions.onBack,
        )
        EventPage.DISCUSSION_THREAD -> EventDiscussionThreadScreen(
            state = state,
            onReply = actions.onCreateDiscussionComment,
            onToggleLike = actions.onToggleDiscussionLike,
            onDeleteComment = actions.onDeleteDiscussionComment,
            onBack = actions.onBack,
        )
    }
}
