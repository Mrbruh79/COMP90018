package com.example.blap.ui.screens.events

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.blap.R
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventInvitationStatus
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.event.EventCheckInState
import com.example.blap.location.GeoCoordinates
import com.example.blap.ui.components.ListRow
import com.example.blap.ui.components.SubScreenHeader
import com.example.blap.ui.theme.ButtonHeightMedium
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
    onShowAnnouncements: () -> Unit,
    onShowDiscussion: () -> Unit,
    onRequestGpsEntry: () -> Unit,
    onScanCheckInQr: () -> Unit,
    onShowCheckInQr: () -> Unit,
    onShowSavedChat: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val event = state.selectedEvent ?: return
    val membership = state.membership
    val now = System.currentTimeMillis()
    val checkedIn = EventCheckInState.isCheckedIn(event, membership, now)
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
        val deletesWholeEvent = membership?.role == com.example.blap.event.EventRole.PRIMARY_ADMIN
        AlertDialog(
            onDismissRequest = { showLeaveConfirmation = false },
            title = {
                Text(if (deletesWholeEvent) "Leave and delete ${event.title}?" else "Leave ${event.title}?")
            },
            text = {
                Text(
                    if (deletesWholeEvent) {
                        "You are the primary admin. Leaving will permanently delete this event " +
                            "and all of its data for everyone."
                    } else {
                        "Leaving permanently deletes this event's messages, announcements, check-in " +
                            "and other local data from this device. This cannot be undone."
                    },
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
                ) {
                    Text(
                        if (deletesWholeEvent) "Leave and delete" else "Leave event",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
    SubScreenHeader(event.title, onBack)
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
    ) {
        item {
            Text(event.title, style = MaterialTheme.typography.headlineSmall)
            if (event.visibility == EventVisibility.PRIVATE) {
                Text(
                    "Private · invite only",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (event.requiresSignIn) {
                Text(
                    "Protected public event · sign-in required to join",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.secondary,
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
            if (membership?.canParticipate == true && !event.isDeleted) {
                Text(
                    if (checkedIn) "You are checked in to the event"
                    else "Check in to access event discussions.",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checkedIn) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (event.isDeleted) {
            item { EventScheduleAndMap(event, context) }
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
                    Button(
                        onClick = onJoin,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                    ) { Text("Join Event") }
                }
            } else {
                item { Text("An accepted in-app invitation is required to join this event.") }
            }
            item { EventScheduleAndMap(event, context) }
        } else {
            if (event.isActive(now)) {
                if (checkedIn) {
                    item {
                        Button(
                            onClick = onShowSavedChat,
                            modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_chat),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("On-site Chat", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    item {
                        Button(
                            onClick = onRequestGpsEntry,
                            modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                        ) { Text("Check in to Event") }
                    }
                    item {
                        OutlinedButton(
                            onClick = onScanCheckInQr,
                            modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_scan_qr),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("Check-in with Event QR", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                item {
                    OutlinedButton(
                        onClick = onShowSavedChat,
                        modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("View saved On-site Chat") }
                }
            }
            item { EventScheduleAndMap(event, context) }
            // ListRow brings its own vertical padding, so the rows and their dividers share one
            // item without extra list spacing — otherwise each divider sits 24dp from the rows
            // above or below it but only 12dp from everything else.
            item {
                Column {
                    HorizontalDivider()
                    ListRow(
                        title = "Announcements",
                        supportingText = "Read announcements from organizers",
                        onClick = onShowAnnouncements,
                    )
                    ListRow(
                        title = "Event discussion",
                        supportingText = "Discuss the event with fellow attendees",
                        onClick = onShowDiscussion,
                    )
                    HorizontalDivider()
                }
            }
            item {
                OutlinedButton(
                    onClick = { showLeaveConfirmation = true },
                    modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(if (membership.role == com.example.blap.event.EventRole.PRIMARY_ADMIN) "Leave and Delete Event" else "Leave Event") }
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
                    val invitationStatus = if (
                        invitation.status == EventInvitationStatus.ACCEPTED &&
                        invitation.recipientUid !in event.memberIds
                    ) {
                        "left"
                    } else {
                        invitation.status.name.lowercase()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(invitation.recipientName.ifBlank { "@${invitation.recipientUsername}" })
                            Text(
                                "@${invitation.recipientUsername} · $invitationStatus",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (invitation.status == EventInvitationStatus.PENDING) {
                            TextButton(onClick = { onRevokeInvitation(invitation.id) }) { Text("Revoke") }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun EventScheduleAndMap(event: CommunityEvent, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        EventIconRow(
            R.drawable.ic_calendar,
            "${formatEventTime(event.startsAt)} - ${formatEventTime(event.endsAt)}",
        )
        if (event.venueName.isNotBlank()) {
            EventIconRow(R.drawable.ic_location, event.venueName)
        }
        EventVenueMap(event)
        OutlinedButton(
            onClick = { openEventInMaps(context, event) },
            modifier = Modifier.fillMaxWidth().height(ButtonHeightMedium),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_location),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("Open in Maps", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun EventVenueMap(event: CommunityEvent) {
    OsmEventMap(
        point = GeoCoordinates(event.latitude, event.longitude),
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
