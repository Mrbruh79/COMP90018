package com.example.blap.ui.screens.profile

import com.example.blap.chat.ContactProfile

data class ProfileActions(
    val onNameChanged: (String) -> Unit,
    val onCompleteSetup: () -> Unit,
    val onShowMyCard: () -> Unit,
    val onShowSettingsScreen: () -> Unit,
    val onEditProfile: () -> Unit,
    val onProfileChanged: (ContactProfile) -> Unit,
    val onSaveProfile: () -> Unit,
    val onCancelProfile: () -> Unit,
    val onShowDiscoverySettings: () -> Unit,
    val onDiscoveryPhoneChanged: (String) -> Unit,
    val onDiscoveryEnabledChanged: (Boolean) -> Unit,
    val onSaveDiscoverySettings: () -> Unit,
    val onCancelDiscoverySettings: () -> Unit,
)
