package com.example.blap.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.ui.components.ListRow
import com.example.blap.ui.components.ListToggleRow
import com.example.blap.ui.components.SubScreenHeader

@Composable
internal fun NotificationSettingsScreen(
    settings: ChatNotificationSettings,
    permissionGranted: Boolean,
    onSettingsChanged: (ChatNotificationSettings) -> Unit,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Notifications", onBack)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 18.dp),
        ) {
            item {
                ListToggleRow(
                    title = "Chat & Event Alerts",
                    supportingText = "Enables chat, event message, announcement and discussion notifications",
                    checked = settings.enabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(enabled = it)) },
                )
            }
            item {
                ListToggleRow(
                    title = "Direct Messages",
                    supportingText = "Show notifications from direct messages",
                    checked = settings.direct,
                    onCheckedChange = { onSettingsChanged(settings.copy(direct = it)) },
                    enabled = settings.enabled,
                )
            }
            item {
                ListToggleRow(
                    title = "Group Messages",
                    supportingText = "Show notifications from group messages",
                    checked = settings.privateGroups,
                    onCheckedChange = { onSettingsChanged(settings.copy(privateGroups = it)) },
                    enabled = settings.enabled,
                )
            }
            item {
                ListToggleRow(
                    title = "Show Message Previews",
                    supportingText = "Show previews of message contents",
                    checked = settings.showPreview,
                    onCheckedChange = { onSettingsChanged(settings.copy(showPreview = it)) },
                    enabled = settings.enabled,
                )
            }
            item {
                ListRow(
                    title = "Manage Notification Permissions",
                    supportingText = if (permissionGranted) "Notifications are enabled"
                    else "Notification permission is not granted",
                    onClick = onRequestPermission,
                    supportingTextColor = if (permissionGranted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            item {
                InfoCard(
                    "Alerts can arrive while CommonGround is in the background and still running. " +
                        "If Android stops the app or you force-stop it, no new alerts are delivered. " +
                        "Reopening the app loads updates without replaying event discussion or announcement alerts.",
                )
            }
        }
    }
}
