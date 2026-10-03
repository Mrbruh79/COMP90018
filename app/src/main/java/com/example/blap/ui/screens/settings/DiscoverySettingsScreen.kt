package com.example.blap.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.ui.components.PhoneNumberFields
import com.example.blap.ui.components.SubScreenHeader
import com.example.blap.ui.screens.auth.AuthActions
import com.example.blap.ui.theme.ButtonHeightExtraSmall
import com.example.blap.ui.theme.ButtonHeightMedium

@Composable
internal fun DiscoverySettingsScreen(
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile,
    authActions: AuthActions,
    lookupPhoneNumber: String,
    enabled: Boolean,
    savedLookupPhoneNumber: String,
    savedEnabled: Boolean,
    onPhoneChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SubScreenHeader("Online Account Discovery", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
        ) {
            item {
                Text(
                    "The information below is shown when discovering the contact information of your online account.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HorizontalDivider() }
            item {
                DiscoveryField(
                    label = "Account Username",
                    value = "@${accountProfile.username}",
                    supportingText = "Username search is not available yet",
                )
            }
            item { HorizontalDivider() }
            item { AccountEmailField(authAccount, authActions) }
            item { HorizontalDivider() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Account Phone Number", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val unsaved = lookupPhoneNumber != savedLookupPhoneNumber || enabled != savedEnabled
                    Text(
                        when {
                            unsaved -> "Unsaved changes. Save below to update online lookup."
                            savedEnabled -> "Phone number is currently discoverable"
                            else -> "Phone number currently not discoverable"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (!unsaved && savedEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            item { PhoneNumberFields(lookupPhoneNumber, "Lookup Number", onPhoneChanged) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        enabled = lookupPhoneNumber.isNotBlank(),
                    )
                    Text("Make my phone number discoverable", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                InfoCard(
                    "Phone numbers are not verified. Your phone number can be used for nearby matching, but cannot be looked up online automatically. Checking the above option enables your phone number to be looked up online.",
                )
            }
        }
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .height(ButtonHeightMedium),
        ) {
            Text("Save Changes")
        }
    }
}

@Composable
private fun AccountEmailField(authAccount: AuthAccount, authActions: AuthActions) {
    var verificationSent by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        DiscoveryField(
            label = "Account E-mail",
            value = authAccount.email.ifBlank { "No email on this account" },
            supportingText = if (authAccount.emailVerified) "Email address verified"
            else "Email address not yet verified",
            supportingTextColor = if (authAccount.emailVerified) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!authAccount.emailVerified && authAccount.email.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    verificationSent = true
                    authActions.onSendVerificationEmail { success -> verificationSent = success }
                },
                modifier = Modifier.height(ButtonHeightExtraSmall),
                enabled = !verificationSent,
                border = BorderStroke(
                    1.dp,
                    if (verificationSent) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    if (verificationSent) "Verification Sent" else "Verify Email",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryField(
    label: String,
    value: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Text(supportingText, style = MaterialTheme.typography.bodySmall, color = supportingTextColor)
    }
}
