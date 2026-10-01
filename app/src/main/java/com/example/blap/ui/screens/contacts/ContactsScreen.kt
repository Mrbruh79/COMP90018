package com.example.blap.ui.screens.contacts

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.chat.SavedContact
import com.example.blap.ui.components.Avatar

@Composable
internal fun ContactsScreen(
    contacts: List<SavedContact>,
    onlineReady: Boolean,
    search: String,
    onSearchChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onMessage: (String) -> Unit,
    onCheckOnline: (String) -> Unit,
    onOpenCreateGroup: () -> Unit,
    onDiscoverNearby: () -> Unit,
) {
    val filtered = contacts.filter { contact ->
        search.isBlank() || listOf(
            contact.name,
            contact.phoneNumber,
            contact.email,
            contact.googleAccountEmail,
            contact.username,
            contact.instagramUrl,
            contact.linkedinUrl,
        ).any { it.contains(search, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Add new contacts", style = MaterialTheme.typography.titleMedium)
        ContactActionRow(R.drawable.ic_contact_add, "Manually add contact", onAdd)
        ContactActionRow(R.drawable.ic_contact_import, "Import phone contacts", onImport)
        ContactActionRow(R.drawable.ic_scan_qr, "Scan QR Code", onScan)
        Text(
            "Saved Contacts",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChanged,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true,
            placeholder = { Text("Search contacts") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            shape = CircleShape,
        )
        ContactActionRow(R.drawable.ic_group_add, "Create group chat", onOpenCreateGroup)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    if (contacts.isEmpty()) {
                        NoSavedContactsCard(onDiscoverNearby)
                    } else {
                        Text(
                            "No contacts match your search",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            } else {
                items(filtered, key = SavedContact::id) { contact ->
                    val linked = contact.linkedPeerId != null || contact.cloudUserId.isNotBlank()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(contact.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(contact.name, contact.linkedPeerId != null)
                                Column(
                                    Modifier
                                        .padding(horizontal = 16.dp)
                                        .weight(1f),
                                ) {
                                    Text(contact.name, style = MaterialTheme.typography.titleMedium)
                                    if (contact.username.isNotBlank()) {
                                        Text("@${contact.username}", color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        contact.email.ifBlank {
                                            contact.googleAccountEmail.ifBlank {
                                                contact.phoneNumber.ifBlank { "BLAP contact" }
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onMessage(contact.id) }) {
                                    Icon(
                                        painterResource(R.drawable.ic_send),
                                        contentDescription = "Message ${contact.name}",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        contact.cloudUserId.isNotBlank() && contact.username.isNotBlank() ->
                                            "Online as @${contact.username}"
                                        contact.cloudUserId.isNotBlank() -> "Online account found. Confirm a number match by QR."
                                        contact.linkedPeerId != null -> "Recognized on mesh"
                                        onlineReady && contact.username.isNotBlank() -> "Can find @${contact.username} online"
                                        onlineReady && (contact.email.isNotBlank() || contact.googleAccountEmail.isNotBlank()) ->
                                            "Can look up a verified account email online"
                                        onlineReady && contact.phoneNumber.isNotBlank() ->
                                            "Can look up this number online. Match is unverified"
                                        else -> "Saved locally. Sign in or pair by QR or Nearby to chat"
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (linked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (onlineReady && (contact.phoneNumber.isNotBlank() ||
                                        contact.email.isNotBlank() || contact.googleAccountEmail.isNotBlank() ||
                                        contact.username.isNotBlank())) {
                                    TextButton(
                                        onClick = { onCheckOnline(contact.id) },
                                        modifier = Modifier.padding(start = 12.dp),
                                    ) { Text("Find online") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSavedContactsCard(onDiscoverNearby: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No saved CommonGround contacts", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a new contact using the menu above, or try our nearby discovery feature!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onDiscoverNearby,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Go to Discover Nearby", style = MaterialTheme.typography.titleSmall) }
        }
    }
}

@Composable
private fun ContactActionRow(@DrawableRes iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            label,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
