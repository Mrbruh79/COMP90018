package com.example.blap.ui.screens.profile

import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import com.example.blap.chat.ContactProfile
import com.example.blap.ui.screens.profile.ProfileForm

@Composable
internal fun ProfileEditorScreen(
    profile: ContactProfile,
    onChanged: (ContactProfile) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    ProfileForm(
        title = "Edit Profile Card",
        subtitle = "These details appear on the QR card you choose to share. Online lookup is managed separately in Settings > Find me.",
        profile = profile,
        onChanged = onChanged,
        onSave = onSave,
        onBack = onBack,
        saveLabel = "Save my card",
    )
}
