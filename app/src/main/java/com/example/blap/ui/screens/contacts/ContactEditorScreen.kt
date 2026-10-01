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
            "Add contact details and any social profiles you want to keep together."
        },
        profile = profile,
        onChanged = onChanged,
        onSave = onSave,
        onBack = onBack,
        saveLabel = if (isExisting) "Save changes" else "Save contact",
        onDelete = if (isExisting) onDelete else null,
        isContact = true,
        allowQrOnly = source == ContactSource.QR,
    )
}
