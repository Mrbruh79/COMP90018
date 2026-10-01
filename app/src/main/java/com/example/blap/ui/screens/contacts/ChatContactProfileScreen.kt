package com.example.blap.ui.screens.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ConversationSummary
import com.example.blap.chat.SavedContact

@Composable
internal fun ChatContactProfileScreen(
    conversation: ConversationSummary?,
    contact: SavedContact?,
    onEdit: () -> Unit,
    onSaveContact: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("Back to chat") }
        Text(contact?.name ?: conversation?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        if (contact == null) {
            Text("This person is not in your saved contacts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onSaveContact, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Save contact")
            }
            return@Column
        }
        Text(
            when {
                contact.cloudUserId.isNotBlank() -> "Online account linked"
                contact.linkedPeerId != null -> "Paired nearby"
                else -> "Saved on this phone"
            }, color = MaterialTheme.colorScheme.primary,
        )
        listOf(
            "BLAP username" to contact.username.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty(),
            "Phone" to contact.phoneNumber,
            "Email" to contact.email,
            "Google account" to contact.googleAccountEmail,
            "About" to contact.bio,
        ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
            Text(value)
        }
        listOf(
            "Website" to contact.websiteUrl,
            "Instagram" to contact.instagramUrl,
            "X" to contact.xUrl,
            "LinkedIn" to contact.linkedinUrl,
            "GitHub" to contact.githubUrl,
        ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
            TextButton(onClick = { runCatching { uriHandler.openUri(value) } }) { Text("Open $label") }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Edit saved contact")
        }
    }
}
