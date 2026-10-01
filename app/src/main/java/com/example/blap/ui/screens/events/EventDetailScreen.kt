package com.example.blap.ui.screens.events

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventInvitationStatus
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.ui.screens.events.formatEventTime

@Composable
internal fun EventDetailScreen(
    state: EventUiState,
    onBeginEdit: () -> Unit,
    onDeleteEvent: () -> Unit,
    onJoin: () -> Unit,
    onInvite: (String) -> Unit,
    onSearchParticipant: (String) -> Unit,
    onRevokeInvitation: (String) -> Unit,
    onLeave: () -> Unit,
    onPromoteMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteLocalData: () -> Unit,
    onShowAnnouncements: () -> Unit,
    onShowDiscussion: () -> Unit,
    onRequestGpsEntry: () -> Unit,
    onRequestAdminAccess: () -> Unit,
    onApproveAdminAccess: (String) -> Unit,
    onScanCheckInQr: () -> Unit,
    onShowCheckInQr: () -> Unit,
    onShowSavedChat: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val event = state.selectedEvent ?: return
    val membership = state.membership
    val now = System.currentTimeMillis()
    var showDeleteConfirmation by remember(event.id) { mutableStateOf(false) }
    var showLeaveConfirmation by remember(event.id) { mutableStateOf(false) }
    var inviteIdentifier by remember(event.id) { mutableStateOf("") }
    var participantIdentifier by remember(event.id) { mutableStateOf("") }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete ${event.title}?") },
            text = {
                Text(
                    "This permanently removes the event, memberships, announcements, discussion and " +
                        "saved on-site chat from every reachable device. This cannot be undone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteEvent()
                    },
                    enabled = !state.loading,
                ) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = { Text("Leave and delete ${event.title}?") },
            text = {
                Text(
                    "You are the primary admin. Leaving will permanently delete this event " +
                        "and all of its data for everyone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmation = false
                        onLeave()
                    },
                    enabled = !state.loading,
                ) { Text("Leave and delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item { TextButton(onClick = onBack) { Text("‹ All events") } }
        item {
            Text(event.title, style = MaterialTheme.typography.headlineMedium)
            if (event.visibility == EventVisibility.PRIVATE) {
                Text(
                    "Private · invite only",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (event.requiresSignIn) {
                Text(
                    "Protected public event · sign-in required to join",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (event.isDeleted) {
                Text(
                    "Deleted by the event admin · saved content is read-only",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(event.description, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.venueName.isNotBlank()) {
                Text(
                    event.venueName,
                    modifier = Modifier.padding(top = 10.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "${formatEventTime(event.startsAt)} – ${formatEventTime(event.endsAt)}",
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            EventVenueMap(event)
            OutlinedButton(
                onClick = { openEventInMaps(context, event) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Open in maps") }
        }
        if (event.isDeleted) {
            if (membership != null) {
                if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                    item {
                        Button(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Finish deleting event") }
                    }
                }
            }
        } else if (membership == null || membership.leftAt != null) {
            if (event.visibility == EventVisibility.PUBLIC) {
                item {
                    Button(onClick = onJoin, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Join event")
                    }
                }
            } else {
                item { Text("An accepted in-app invitation is required to join this event.") }
            }
        } else {
            if (membership.isAdmin) {
                item {
                    OutlinedButton(onClick = onBeginEdit, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit event")
                    }
                }
            }
            item { Button(onClick = onShowAnnouncements, modifier = Modifier.fillMaxWidth()) { Text("Announcements") } }
            item {
                Button(onClick = onShowDiscussion, modifier = Modifier.fillMaxWidth()) {
                    Text("Event discussion")
                }
            }
            if (event.isActive(now)) {
                item { Button(onClick = onRequestGpsEntry, modifier = Modifier.fillMaxWidth()) { Text("Enter on-site chat") } }
                if (event.visibility == EventVisibility.PUBLIC) {
                    item { OutlinedButton(onClick = onScanCheckInQr, modifier = Modifier.fillMaxWidth()) { Text("Check in with venue QR") } }
                    if (membership.isAdmin) {
                        item { OutlinedButton(onClick = onShowCheckInQr, modifier = Modifier.fillMaxWidth()) { Text("Display venue check-in QR") } }
                    }
                } else {
                    item {
                        OutlinedButton(
                            onClick = onRequestAdminAccess,
                            enabled = !state.waitingForAdminAccess,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.waitingForAdminAccess) "Waiting for an admin…" else "Request admin access")
                        }
                    }
                }
            } else {
                item { OutlinedButton(onClick = onShowSavedChat, modifier = Modifier.fillMaxWidth()) { Text("View saved on-site chat") } }
            }
            item {
                OutlinedButton(
                    onClick = {
                        if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                            showLeaveConfirmation = true
                        } else {
                            onLeave()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) "Leave and delete event" else "Leave event") }
            }
            if (membership.isAdmin) {
                item {
                    Text("Find participant", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Search active participants using an exact @username or verified account email.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = participantIdentifier,
                            onValueChange = { participantIdentifier = it.take(120) },
                            label = { Text("Username or email") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onSearchParticipant(participantIdentifier) },
                            enabled = participantIdentifier.isNotBlank() && !state.loading,
                        ) { Text("Search") }
                    }
                }
                state.participantSearchResult?.let { result ->
                    item(key = "participant-search-${result.membership.userId}") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(result.membership.displayName, style = MaterialTheme.typography.titleMedium)
                                    if (result.username.isNotBlank()) {
                                        Text("@${result.username}", color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        result.membership.role.name.replace('_', ' ').lowercase(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onRemoveMember(result.membership.userId) },
                                    enabled = !state.loading,
                                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) {
                item { Text("Event team", style = MaterialTheme.typography.titleMedium) }
                items(state.members.filter { it.canParticipate }, key = { "member-${it.userId}" }) { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(member.displayName)
                            Text(member.role.name.replace('_', ' ').lowercase(), style = MaterialTheme.typography.bodySmall)
                        }
                        if (
                            member.userId != membership.userId &&
                            member.role != com.example.blap.event.EventRole.PRIMARY_ADMIN
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (member.role == com.example.blap.event.EventRole.ATTENDEE) {
                                    TextButton(onClick = { onPromoteMember(member.userId) }) {
                                        Text("Make co-admin")
                                    }
                                }
                                TextButton(onClick = { onRemoveMember(member.userId) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete event", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (event.visibility == EventVisibility.PRIVATE && membership.isAdmin) {
                item { Text("Invite people", style = MaterialTheme.typography.titleMedium) }
                item {
                    Text(
                        "Enter an exact @username or verified CommonGround account email.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = inviteIdentifier,
                            onValueChange = { inviteIdentifier = it.take(120) },
                            label = { Text("Username or email") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                onInvite(inviteIdentifier)
                                inviteIdentifier = ""
                            },
                            enabled = inviteIdentifier.isNotBlank() && !state.loading,
                        ) { Text("Invite") }
                    }
                }
                items(state.eventInvitations, key = { "outgoing-${it.id}" }) { invitation ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(invitation.recipientName.ifBlank { "@${invitation.recipientUsername}" })
                            Text(
                                "@${invitation.recipientUsername} · ${invitation.status.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (invitation.status == EventInvitationStatus.PENDING) {
                            TextButton(onClick = { onRevokeInvitation(invitation.id) }) { Text("Revoke") }
                        }
                    }
                }
                if (state.accessRequests.isNotEmpty()) {
                    item { Text("Nearby access requests", style = MaterialTheme.typography.titleMedium) }
                    items(state.accessRequests, key = { "access-${it.id}" }) { request ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(request.displayName)
                                Text("Accepted member waiting nearby", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onApproveAdminAccess(request.id) }) { Text("Allow") }
                        }
                    }
                }
            }
            item { TextButton(onClick = onDeleteLocalData, modifier = Modifier.fillMaxWidth()) { Text("Delete local event data") } }
        }
    }
}

@Composable
private fun EventVenueMap(event: CommunityEvent) {
    OsmEventMap(
        point = OsmPoint(event.latitude, event.longitude),
        radiusMetres = event.radiusMetres,
        height = 220.dp,
    )
}

private fun openEventInMaps(context: Context, event: CommunityEvent) {
    val label = Uri.encode(event.venueName.ifBlank { event.title })
    val geoUri = "geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}($label)".toUri()
    val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri)
    val intent = if (mapsIntent.resolveActivity(context.packageManager) != null) {
        mapsIntent
    } else {
        Intent(
            Intent.ACTION_VIEW,
            "https://www.openstreetmap.org/?mlat=${event.latitude}&mlon=${event.longitude}#map=17/${event.latitude}/${event.longitude}".toUri(),
        )
    }
    context.startActivity(intent)
}
