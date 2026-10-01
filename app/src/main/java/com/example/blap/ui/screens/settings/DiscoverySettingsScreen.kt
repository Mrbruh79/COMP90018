package com.example.blap.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.ui.components.PhoneNumberFields

@Composable
internal fun DiscoverySettingsScreen(
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile,
    lookupPhoneNumber: String,
    enabled: Boolean,
    savedLookupPhoneNumber: String,
    savedEnabled: Boolean,
    onlineLookupStatus: String,
    onPhoneChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text("Find me", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            item {
                Text("These account details help others find you. They are separate from the phone, email and links shown on your QR contact card.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Account username", style = MaterialTheme.typography.titleMedium)
                        Text("@${accountProfile.username}")
                        Text("Username search is not available yet. Share your QR card to pair directly.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sign-in email", style = MaterialTheme.typography.titleMedium)
                        Text(authAccount.email.ifBlank { "No email on this account" })
                        Text(
                            if (authAccount.emailVerified) "People can find this account by its exact email address."
                            else "Verify this email before others can find your account by it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Text("Phone lookup", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(if (lookupPhoneNumber != savedLookupPhoneNumber || enabled != savedEnabled)
                    "Unsaved changes. Save below to update online lookup."
                    else onlineLookupStatus,
                    color = MaterialTheme.colorScheme.primary)
            }
            item { PhoneNumberFields(lookupPhoneNumber, "Lookup number", onPhoneChanged) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = onEnabledChanged,
                        enabled = lookupPhoneNumber.isNotBlank())
                    Text("Let people find my account by this number")
                }
            }
            item {
                Text("This number is not verified. The number on your shared card can help nearby mesh matching, but it does not enable online lookup. Anyone who knows your lookup number may find this account once you save with the box checked.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text("Save discovery settings")
        }
    }
}
