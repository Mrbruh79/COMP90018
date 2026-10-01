package com.example.blap.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class ProfileViewModel(private val session: MessagingSession) : androidx.lifecycle.ViewModel() {
    val uiState = session.uiState
    fun applyAccountProfile(username: String, displayName: String) =
        session.accountPublisher.applyAccountProfile(username, displayName)
    fun restorePrivateProfile(snapshot: PrivateProfileSnapshot?, username: String, displayName: String) =
        session.profileBackup.restorePrivateProfile(snapshot, username, displayName)

    fun updateDisplayName(name: String) {
        session.state.update { it.copy(displayName = name.take(ChatLimits.MAX_NAME_LENGTH), nameError = null) }
    }

    fun updatePhoneNumber(phoneNumber: String) {
        session.state.update { it.copy(phoneNumber = phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH), phoneError = null) }
    }

    fun completeSetup() {
        when (val check = session.identityService.checkIdentity()) {
            is IdentityCheck.Invalid ->
                session.state.update { it.copy(nameError = check.nameError, phoneError = check.phoneError) }

            is IdentityCheck.Valid -> {
                session.identityService.saveIdentity(check.name, check.phone)
                session.navigation.showConversationList()
                session.workScope.launch { session.accountPublisher.publishAccount() }
            }
        }
    }

    fun showMyCard() {
        session.state.update { it.copy(screen = ChatScreen.SHOWING_MY_CARD, error = null) }
    }

    fun editProfile() {
        session.profileReturnScreen = session.state.value.screen
        session.state.update { it.copy(screen = ChatScreen.EDITING_PROFILE, profileDraft = session.currentProfile(), error = null) }
    }

    fun showDiscoverySettings() {
        session.state.update { it.copy(screen = ChatScreen.DISCOVERY_SETTINGS, profileDraft = session.currentProfile(), error = null) }
    }

    fun updateDiscoveryPhone(phoneNumber: String) {
        session.state.update { state -> state.copy(profileDraft =
            (state.profileDraft ?: session.currentProfile()).copy(lookupPhoneNumber = phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH))) }
    }

    fun updateDiscoveryEnabled(enabled: Boolean) {
        session.state.update { state -> state.copy(profileDraft =
            (state.profileDraft ?: session.currentProfile()).copy(discoverableByPhone = enabled)) }
    }

    fun cancelDiscoveryEdit() {
        session.state.update { it.copy(screen = ChatScreen.SETTINGS, profileDraft = null, error = null) }
    }

    fun saveDiscoverySettings() {
        val draft = session.state.value.profileDraft ?: session.currentProfile()
        val lookupPhone = if (draft.lookupPhoneNumber.isBlank()) "" else
            PhoneIdentity.normalizeInternational(draft.lookupPhoneNumber)
        if (lookupPhone == null || (draft.discoverableByPhone && lookupPhone.isBlank())) {
            session.showError("Enter a valid phone number with its country code before enabling phone lookup.")
            return
        }
        val saved = session.currentProfile().copy(
            lookupPhoneNumber = lookupPhone,
            discoverableByPhone = draft.discoverableByPhone && lookupPhone.isNotBlank(),
        )
        session.identityStore.saveProfile(saved)
        session.profileBackup.save(saved, session.identityStore.profileUpdatedAt())
        session.state.update { it.copy(
            profileLookupPhoneNumber = saved.lookupPhoneNumber,
            profileDiscoverableByPhone = saved.discoverableByPhone,
            profileDraft = null,
            screen = ChatScreen.SETTINGS,
            onlineLookupStatus = "Updating online lookup",
            notice = "Discovery settings saved. Updating online lookup.",
        ) }
        session.workScope.launch { session.accountPublisher.publishAccount(reportSuccess = true) }
    }

    fun updateProfile(profile: ContactProfile) {
        session.state.update { it.copy(profileDraft = profile.copy(
            displayName = profile.displayName.take(ChatLimits.MAX_NAME_LENGTH),
            phoneNumber = profile.phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH),
            email = profile.email.take(ChatLimits.MAX_EMAIL_LENGTH),
            googleAccountEmail = profile.googleAccountEmail.take(ChatLimits.MAX_EMAIL_LENGTH),
            bio = profile.bio.take(ChatLimits.MAX_BIO_LENGTH),
            websiteUrl = profile.websiteUrl.take(ChatLimits.MAX_URL_LENGTH),
            instagramUrl = profile.instagramUrl.take(ChatLimits.MAX_URL_LENGTH),
            xUrl = profile.xUrl.take(ChatLimits.MAX_URL_LENGTH),
            linkedinUrl = profile.linkedinUrl.take(ChatLimits.MAX_URL_LENGTH),
            githubUrl = profile.githubUrl.take(ChatLimits.MAX_URL_LENGTH),
        )) }
    }

    fun cancelProfileEdit() {
        session.state.update { it.copy(screen = session.profileReturnScreen, profileDraft = null, error = null) }
    }

    fun saveProfile() {
        val wasActive = session.state.value.nearbyActive
        val profile = session.state.value.profileDraft ?: session.currentProfile()
        val normalizedPhone = if (profile.phoneNumber.isBlank()) "" else
            PhoneIdentity.normalizeInternational(profile.phoneNumber)
        val email = if (profile.email.isBlank()) "" else ContactIdentity.normalizeEmail(profile.email)
        val googleEmail = if (profile.googleAccountEmail.isBlank()) "" else
            ContactIdentity.normalizeEmail(profile.googleAccountEmail)
        if (profile.displayName.trim().isBlank() || normalizedPhone == null || email == null ||
            googleEmail == null) {
            session.showError("Enter your name and check any phone or email addresses you added.")
            return
        }
        val discovery = session.currentProfile()
        val saved = profile.copy(
            displayName = profile.displayName.trim(),
            phoneNumber = normalizedPhone,
            email = email,
            googleAccountEmail = googleEmail,
            lookupPhoneNumber = discovery.lookupPhoneNumber,
            discoverableByPhone = discovery.discoverableByPhone,
            bio = profile.bio.trim(),
            websiteUrl = ProfileUrl.normalize(profile.websiteUrl),
            instagramUrl = ProfileUrl.normalize(profile.instagramUrl),
            xUrl = ProfileUrl.normalize(profile.xUrl),
            linkedinUrl = ProfileUrl.normalize(profile.linkedinUrl),
            githubUrl = ProfileUrl.normalize(profile.githubUrl),
        )
        session.identityStore.saveProfile(saved)
        session.profileBackup.save(saved, session.identityStore.profileUpdatedAt())
        session.localPhoneHash = PhoneIdentity.hash(normalizedPhone).orEmpty()
        session.applyProfile(saved)
        if (wasActive) {
            session.nearbyTransport.stop()
            session.connectedPeers.clear()
            session.state.update {
                it.copy(
                    discoveredDevices = emptyList(),
                    directConnectionCount = 0,
                    conversations = it.conversations.map { conversation ->
                        conversation.copy(connected = false)
                    },
                )
            }
            session.nearbyTransport.startAdvertising(saved.displayName, session.localPeerId, session.localPhoneHash)
            if (session.state.value.nearbyActive) session.nearbyTransport.startDiscovery()
        }
        session.state.update { it.copy(screen = session.profileReturnScreen, profileDraft = null, notice = "Profile saved.") }
        session.workScope.launch { session.accountPublisher.publishAccount() }
    }

    fun showSettings() {
        session.state.update { it.copy(screen = ChatScreen.SETTINGS, error = null) }
    }

}
