package com.example.blap.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventUiState
import com.example.blap.event.EventVisibility
import com.example.blap.ui.screens.events.formatEventTime

@Composable
internal fun EventListScreen(
    state: EventUiState,
    onBeginCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onAcceptInvitation: (String) -> Unit,
    onDeclineInvitation: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Join online, connect on-site",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBeginCreate) { Text("Create") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            if (state.showingOfflineEvents) {
                item {
                    Text(
                        "Offline: showing joined events saved on this device.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (state.invitations.isNotEmpty()) {
                item { Text("Private invitations", style = MaterialTheme.typography.titleLarge) }
                items(state.invitations, key = { "invite-${it.id}" }) { invitation ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(invitation.eventTitle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Invited by ${invitation.inviterName} · ${formatEventTime(invitation.startsAt)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onAcceptInvitation(invitation.id) },
                                    enabled = !state.loading,
                                ) { Text("Accept") }
                                OutlinedButton(
                                    onClick = { onDeclineInvitation(invitation.id) },
                                    enabled = !state.loading,
                                ) { Text("Decline") }
                            }
                        }
                    }
                }
                item { Text("Events", style = MaterialTheme.typography.titleLarge) }
            }
            if (state.events.isEmpty()) {
                item {
                    Text(
                        if (state.loading) "Loading events…" else "No events available yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.events, key = CommunityEvent::id) { event ->
                Card(
                    onClick = { onOpen(event.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        if (event.visibility == EventVisibility.PRIVATE) {
                            Text(
                                "Private · invite only",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        } else if (event.requiresSignIn) {
                            Text(
                                "Protected · sign-in required to join",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (event.isDeleted) {
                            Text(
                                "Deleted · read-only",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (event.description.isNotBlank()) {
                            Text(
                                event.description,
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(
                            listOfNotNull(
                                event.venueName.takeIf(String::isNotBlank),
                                "${formatEventTime(event.startsAt)} · ${event.radiusMetres.toInt()} m venue area",
                            ).joinToString("\n"),
                            modifier = Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
