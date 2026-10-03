package com.example.blap.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ContactProfile
import com.example.blap.ui.components.DeleteConfirmationDialog
import com.example.blap.ui.components.PhoneNumberFields
import com.example.blap.ui.components.SubScreenHeader

@Composable
internal fun ProfileForm(
    title: String,
    subtitle: String,
    profile: ContactProfile,
    onChanged: (ContactProfile) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    saveLabel: String,
    onDelete: (() -> Unit)? = null,
    isContact: Boolean = false,
    allowQrOnly: Boolean = false,
    saving: Boolean = false,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SubScreenHeader(title, onBack)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                ProfileTextField("Name", profile.displayName) {
                    onChanged(profile.copy(displayName = it))
                }
            }
            if (isContact) item {
                ProfileTextField("BLAP username", profile.username) {
                    onChanged(profile.copy(username = it))
                }
            }
            item {
                PhoneNumberFields(profile.phoneNumber,
                    if (isContact) "Contact phone" else "Phone shown on card") { number ->
                    onChanged(profile.copy(
                        phoneNumber = number,
                    ))
                }
            }
            item {
                ProfileTextField("Email", profile.email, KeyboardType.Email) {
                    onChanged(profile.copy(email = it))
                }
            }
            item {
                ProfileTextField("Google account email", profile.googleAccountEmail, KeyboardType.Email) {
                    onChanged(profile.copy(googleAccountEmail = it))
                }
            }
            item {
                Text(
                    "A Google account is added by its email address. A saved address does not prove the account belongs to that person.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = profile.bio,
                    onValueChange = { onChanged(profile.copy(bio = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("About") },
                    placeholder = { Text("A short intro") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(15.dp),
                )
            }
            item {
                Text(
                    "Links & Social Media",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                ProfileTextField("Website", profile.websiteUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(websiteUrl = it))
                }
            }
            item {
                ProfileTextField("Instagram URL", profile.instagramUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(instagramUrl = it))
                }
            }
            item {
                ProfileTextField("X / Twitter URL", profile.xUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(xUrl = it))
                }
            }
            item {
                ProfileTextField("LinkedIn URL", profile.linkedinUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(linkedinUrl = it))
                }
            }
            item {
                ProfileTextField("GitHub URL", profile.githubUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(githubUrl = it))
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = !saving && profile.displayName.isNotBlank() &&
                (!isContact || profile.phoneNumber.isNotBlank() || profile.email.isNotBlank() ||
                    profile.googleAccountEmail.isNotBlank() || profile.username.isNotBlank() || allowQrOnly),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(15.dp),
        ) { Text(saveLabel) }
        if (onDelete != null) {
            TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Delete contact", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirmingDelete && onDelete != null) {
        DeleteConfirmationDialog(
            title = "Delete this contact?",
            message = "Their saved contact card will be removed from BLAP.",
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(15.dp),
    )
}
