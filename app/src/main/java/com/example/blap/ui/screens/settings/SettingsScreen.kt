package com.example.blap.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
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
    checkingVenue: Boolean,
    onCheckVenue: () -> Unit,
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
            SettingsCard(
                title = "Nearby messaging",
                detail = if (nearbyActive) "On. Other phones can find and connect to you." else "Off. Your saved chats are still available.",
                action = if (nearbyActive) "Turn off" else "Turn on",
                onClick = if (nearbyActive) onStopNearby else onStartNearby,
            )
        }
        item {
            SettingsCard(
                title = "Manage Notifications",
                detail = "Choose when you want to be notified by the app",
                action = "Open",
                onClick = onShowNotificationSettings,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nearby places", style = MaterialTheme.typography.titleMedium)
                    Text(venueStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = onCheckVenue, enabled = !checkingVenue) {
                        Text(if (checkingVenue) "Checking..." else "Find a place")
                    }
                }
            }
        }
        item {
            SettingsCard(
                title = "My profile and QR",
                detail = "Choose what appears when someone scans your card",
                action = "Edit",
                onClick = onEditProfile,
            )
        }
        if (!authAccount.isAnonymous) {
            item {
                SettingsCard(
                    title = "Find me",
                    detail = "Username, sign-in email and optional phone lookup",
                    action = "Open",
                    onClick = onShowDiscoverySettings,
                )
            }
        }
        item {
            SettingsCard(
                title = "Android permissions",
                detail = "Nearby devices, contacts, and location access",
                action = "Open",
                onClick = onOpenAppSettings,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("On this phone", style = MaterialTheme.typography.titleMedium)
                    Text("$contactCount saved contact card${if (contactCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$connectionCount active mesh link${if (connectionCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "Open public events and nearby messaging work as a guest. Sign in for protected or private events and online chat sync.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}
