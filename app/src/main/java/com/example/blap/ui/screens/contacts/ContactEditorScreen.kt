package com.example.blap.ui.screens.contacts

import androidx.compose.runtime.Composable
import com.example.blap.chat.ContactProfile
import com.example.blap.chat.ContactSource
import com.example.blap.ui.screens.profile.ProfileForm

@Composable
internal fun ContactEditorScreen(
    profile: ContactProfile,
    source: ContactSource,
    isExisting: Boolean,
    saving: Boolean,
    onChanged: (ContactProfile) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    ProfileForm(
        title = if (isExisting) "Edit contact" else if (source == ContactSource.QR) "Review scanned card" else "New contact",
        subtitle = if (source == ContactSource.QR) {
            "Check the username before saving. It links this card to their online account."
        } else {
            "Enter their username, phone or email. CommonGround finds their account before saving."
        },
        profile = profile,
        onChanged = { if (!saving) onChanged(it) },
        onSave = onSave,
        onBack = onBack,
        saveLabel = if (saving) "Finding account..." else if (isExisting) "Save changes" else "Find account and save",
        saving = saving,
        onDelete = if (isExisting) onDelete else null,
        isContact = true,
        allowQrOnly = source == ContactSource.QR,
    )
}
