package com.example.blap.ui.screens.messages

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.chat.ConversationSummary
import com.example.blap.chat.ConversationType
import com.example.blap.chat.NearbyDevice
import com.example.blap.ui.components.Avatar
import com.example.blap.ui.theme.ButtonHeightExtraSmall
import com.example.blap.ui.theme.ButtonHeightMedium
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConversationList(
    conversations: List<ConversationSummary>,
    devices: List<NearbyDevice>,
    onOpenConversation: (String) -> Unit,
    onConnect: (String) -> Unit,
    onManageContacts: () -> Unit,
    search: String,
    onSearchChanged: (String) -> Unit,
    nearbyActive: Boolean,
    connectionCount: Int,
    deniedPermissions: List<String>,
    onStartNearby: () -> Unit,
    onStopNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    showAccountPrompt: Boolean,
    onOpenAccount: () -> Unit,
) {
    val searching = search.isNotBlank()
    val searchResults = conversations.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.lastMessage.contains(search, ignoreCase = true)
    }
    val nearbyChat = conversations.firstOrNull { it.type == ConversationType.OPEN_MESH }
    val recentChats = conversations.filter { it.type != ConversationType.OPEN_MESH }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (showAccountPrompt && !searching) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccount),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Sign in to your account to enable online chats and contacts sync.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text("Search messages") },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                shape = CircleShape,
            )
        }
        if (searching) {
            item { SectionHeader("Search Result") }
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        "No conversations match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(searchResults, key = ConversationSummary::peerId) { conversation ->
                ConversationCard(conversation) { onOpenConversation(conversation.peerId) }
            }
        } else {
            if (recentChats.isNotEmpty()) {
                item { SectionHeader("Recent Chats") }
                items(recentChats, key = ConversationSummary::peerId) { conversation ->
                    ConversationCard(conversation) { onOpenConversation(conversation.peerId) }
                }
            }
            if (nearbyChat != null) {
                item {
                    SectionHeader("Nearby Chat", "Chat with nearby CommonGround users.")
                }
                item {
                    ConversationCard(nearbyChat) { onOpenConversation(nearbyChat.peerId) }
                }
                item {
                    Text(
                        "Your information will not be shared until you connect with someone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionHeader(
                    "Discover Nearby Contacts",
                    "Discover and connect with other CommonGround users.",
                )
                if (nearbyActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (connectionCount > 0) {
                                    "$connectionCount phone${if (connectionCount == 1) "" else "s"} connected"
                                } else {
                                    "Nearby search is currently active"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "Your account is discoverable to nearby devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            NearbyToggleButton(
                                label = "Turn off nearby",
                                filled = true,
                                onClick = onStopNearby,
                            )
                        }
                    }
                } else {
                    NearbyToggleButton(
                        label = "Turn on nearby",
                        filled = false,
                        onClick = onStartNearby,
                    )
                }
                if (deniedPermissions.isNotEmpty()) {
                    Text(
                        "Permissions must be enabled to be able to use the nearby feature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .height(ButtonHeightExtraSmall),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Open permissions", style = MaterialTheme.typography.titleSmall) }
                }
            }
            if (nearbyActive) {
                items(devices, key = NearbyDevice::endpointId) { device ->
                    DeviceRow(device) { onConnect(device.endpointId) }
                }
                item {
                    Text(
                        if (devices.isEmpty()) {
                            "Devices you can connect with will be shown here."
                        } else {
                            "${devices.size} device${if (devices.size == 1) "" else "s"} ready to connect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyToggleButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            painterResource(R.drawable.ic_bluetooth),
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
    val modifier = Modifier
        .fillMaxWidth()
        .padding(top = 16.dp)
        .height(ButtonHeightMedium)
    if (filled) {
        Button(onClick = onClick, modifier = modifier, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            content = content,
        )
    }
}

@Composable
private fun DeviceRow(device: NearbyDevice, onConnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(device.name, connected = true)
        Text(
            device.name,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onConnect,
            modifier = Modifier.semantics {
                contentDescription = "Connect to ${device.name}"
            },
        ) { Text("Connect") }
    }
}

/**
 * A conversation is reachable when a message sent now can actually arrive: over the nearby mesh,
 * or via a linked online account. Mesh chat has no cloud path, so it reduces to [connected].
 */

internal fun ConversationSummary.reachable() = connected || onlineAccountLinked

private fun ConversationSummary.emptyPreview() = when (type) {
    ConversationType.OPEN_MESH -> "Discover connections with public nearby chat."
    else -> "Say hello"
}

@DrawableRes
private fun ConversationSummary.avatarIcon(): Int? = when (type) {
    ConversationType.OPEN_MESH -> R.drawable.ic_nearby_chat
    ConversationType.PRIVATE_GROUP -> R.drawable.ic_contacts
    ConversationType.DIRECT -> null
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConversationCard(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(conversation.name, conversation.reachable(), conversation.avatarIcon())
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(conversation.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conversation.lastMessageAt > 0 && conversation.lastMessage.isNotBlank()) {
                        Text(formatConversationTime(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(conversation.lastMessage.ifBlank { conversation.emptyPreview() }, maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun formatConversationTime(time: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val pattern = if (today.format(Date(time)) == today.format(Date())) "h:mm a" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
}
