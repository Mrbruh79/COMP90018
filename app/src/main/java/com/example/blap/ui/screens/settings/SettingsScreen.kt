package com.example.blap.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.ui.components.SettingsRow
import com.example.blap.ui.components.SettingsToggleRow
import com.example.blap.ui.screens.auth.AccountAccess
import com.example.blap.ui.screens.auth.AuthActions

@Composable
internal fun SettingsScreen(
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile,
    authActions: AuthActions,
    contactCount: Int,
    connectionCount: Int,
    onEditProfile: () -> Unit,
    onShowDiscoverySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    nearbyActive: Boolean,
    onStartNearby: () -> Unit,
    onStopNearby: () -> Unit,
    venueStatus: String,
    nearbyPlacesOn: Boolean,
    onCheckVenue: () -> Unit,
    onClearVenue: () -> Unit,
    onShowNotificationSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(accountProfile.displayName, style = MaterialTheme.typography.titleLarge)
                    if (accountProfile.username.isNotBlank()) Text("@${accountProfile.username}")
                    else Text("Guest profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (authAccount.email.isNotBlank()) Text(authAccount.email)
                    if (!authAccount.isAnonymous) {
                        TextButton(onClick = authActions.onSignOut) { Text("Sign out") }
                    }
                }
            }
        }
        item {
            AccountAccess(
                authAccount = authAccount,
                actions = authActions,
            )
        }
        item {
            SettingsToggleRow(
                title = "Discover Nearby Devices",
                supportingText = if (nearbyActive) "You are now visible to nearby users."
                else "Make yourself visible to nearby users",
                checked = nearbyActive,
                onCheckedChange = { if (it) onStartNearby() else onStopNearby() },
            )
        }
        item {
            SettingsRow(
                title = "Edit Profile Card",
                supportingText = "Choose what you share on your contact card",
                onClick = onEditProfile,
            )
        }
        if (!authAccount.isAnonymous) {
            item {
                SettingsRow(
                    title = "Online Account Discovery",
                    supportingText = "Manage your online account information",
                    onClick = onShowDiscoverySettings,
                )
            }
        }
        item {
            SettingsToggleRow(
                title = "Nearby Places",
                supportingText = venueStatus.ifBlank { "Find nearby places using your location" },
                checked = nearbyPlacesOn,
                onCheckedChange = { if (it) onCheckVenue() else onClearVenue() },
            )
        }
        item {
            SettingsRow(
                title = "Manage Notifications",
                supportingText = "Choose when you want to be notified by the app",
                onClick = onShowNotificationSettings,
            )
        }
        item {
            SettingsRow(
                title = "Manage Android Permissions",
                supportingText = "Nearby devices, contacts, and location access",
                onClick = onOpenAppSettings,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Saved data information", style = MaterialTheme.typography.titleMedium)
                    Text("$contactCount saved contact card${if (contactCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$connectionCount active mesh link${if (connectionCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "CommonGround exchanges chat data over nearby mesh links. Contact cards are shared only when you display or scan their QR code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
