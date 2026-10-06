package com.example.blap.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.chat.ContactCardCodec
import com.example.blap.chat.ContactProfile
import com.example.blap.chat.ProfileUrl
import com.example.blap.ui.components.createQrBitmap

@Composable
internal fun MyCardScreen(profile: ContactProfile, peerId: String, onEdit: () -> Unit) {
    val payload = remember(profile, peerId) { ContactCardCodec.encode(profile, peerId) }
    val bitmap = remember(payload) { createQrBitmap(payload) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Card(
                modifier = Modifier.padding(top = 20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(24.dp),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR code for ${profile.displayName}'s CommonGround contact card",
                    modifier = Modifier
                        .size(248.dp)
                        .padding(24.dp),
                )
            }
        }
        item {
            Text(
                "Share this QR to other CommonGround users to have them add you as a contact",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                            ) {
                                Text(
                                    profile.displayName.ifBlank { "Your name" },
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                if (profile.username.isNotBlank()) Text(
                                    "@${profile.username}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (profile.phoneNumber.isNotBlank()) Text(
                                    profile.phoneNumber,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = onEdit,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Text("Edit", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (profile.bio.isNotBlank()) {
                            Text(profile.bio, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    val items = listOf(
                        ProfileItem("Email", profile.email, openable = false),
                        ProfileItem("Google account", profile.googleAccountEmail, openable = false),
                        ProfileItem("Website", profile.websiteUrl, ProfileUrl.isOpenable(profile.websiteUrl)),
                        ProfileItem("Instagram", profile.instagramUrl, ProfileUrl.isOpenable(profile.instagramUrl)),
                        ProfileItem("X / Twitter", profile.xUrl, ProfileUrl.isOpenable(profile.xUrl)),
                        ProfileItem("LinkedIn", profile.linkedinUrl, ProfileUrl.isOpenable(profile.linkedinUrl)),
                        ProfileItem("GitHub", profile.githubUrl, ProfileUrl.isOpenable(profile.githubUrl)),
                    ).filter { it.value.isNotBlank() }
                    if (items.isNotEmpty()) {
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items.forEach { ProfileItemRow(it) }
                        }
                    }
                }
            }
        }
    }
}

private data class ProfileItem(
    val label: String,
    val value: String,
    val openable: Boolean = true,
)

@Composable
private fun ProfileItemRow(item: ProfileItem) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.openable) {
            IconButton(
                onClick = {
                    runCatching { uriHandler.openUri(ProfileUrl.normalize(item.value)) }
                },
            ) {
                Icon(
                    painterResource(R.drawable.ic_open_in_new),
                    contentDescription = "Open ${item.label}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
