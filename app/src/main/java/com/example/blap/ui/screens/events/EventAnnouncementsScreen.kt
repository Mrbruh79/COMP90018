package com.example.blap.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.blap.event.EventUiState
import com.example.blap.event.EventCheckInState
import com.example.blap.ui.components.SubScreenHeader
import com.example.blap.ui.components.MessageComposer
import com.example.blap.ui.screens.events.formatEventTime

@Composable
internal fun EventAnnouncementsScreen(
    state: EventUiState,
    onPublish: (String) -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember(state.selectedEventId) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Announcements", onBack)
        Text(
            if (state.selectedEvent?.let { EventCheckInState.isCheckedIn(it, state.membership, System.currentTimeMillis()) } == true)
                "Checked in · keep Nearby on for event mesh sync"
            else "Not checked in · online updates only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            if (state.announcements.isEmpty()) item { Text("No announcements yet") }
            items(state.announcements, key = { it.id }) { announcement ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(announcement.adminName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(announcement.text, modifier = Modifier.padding(top = 5.dp))
                        Text(formatEventTime(announcement.createdAt), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.membership?.isAdmin == true && state.selectedEvent?.isDeleted == false) {
            MessageComposer(
                text = draft,
                onTextChanged = { draft = it },
                onSend = { onPublish(it); draft = "" },
            )
        } else {
            Text("Only event admins can post.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}
