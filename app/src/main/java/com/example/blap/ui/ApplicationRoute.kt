package com.example.blap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blap.NearbyPermissions
import com.example.blap.application.ApplicationViewModels
import com.example.blap.ui.screens.onboarding.OnboardingScreen
import com.example.blap.ui.screens.auth.AuthActions
import com.example.blap.ui.screens.groups.GroupsActions
import com.example.blap.ui.screens.profile.ProfileActions
import com.example.blap.ui.screens.contacts.ContactsActions
import com.example.blap.ui.screens.chat.ChatActions
import com.example.blap.ui.screens.events.EventActions

/** Connects retained state and feature actions to Compose; no Android launchers live here. */
@Composable
fun ApplicationRoute(models: ApplicationViewModels) {
    val applicationViewModel = models.application
    val viewModel = models.chat
    val contactsViewModel = models.contacts
    val groupsViewModel = models.groups
    val profileViewModel = models.profile
    val authViewModel = models.auth
    val eventViewModel = models.events
    val applicationState by applicationViewModel.uiState.collectAsStateWithLifecycle()
    if (applicationState.switchingAccount) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (applicationState.showOnboarding) {
        OnboardingScreen(onGetStarted = applicationViewModel::completeOnboarding)
        return
    }
    val chatUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val eventUiState by eventViewModel.uiState.collectAsStateWithLifecycle()
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val authAccount = authUiState.account
    val accountProfile = authUiState.profile
    val accountProfileLoading = authUiState.profileLoading
    NearbyChatApp(
        chatUiState = chatUiState,
        eventUiState = eventUiState,
        deniedPermissions = applicationState.deniedNearby.map(NearbyPermissions::displayName),
        authAccount = authAccount,
        accountProfile = accountProfile,
        accountProfileLoading = accountProfileLoading,
        groupsActions = GroupsActions(
            onOpenCreateGroup = groupsViewModel::beginCreateGroup,
            onBeginGroupSettings = groupsViewModel::beginGroupSettings,
            onGroupNameChanged = groupsViewModel::updateGroupName,
            onToggleGroupMember = groupsViewModel::toggleGroupMember,
            onCreateGroup = groupsViewModel::createPrivateGroup,
            onSaveGroupSettings = groupsViewModel::saveGroupSettings,
        ),
        profileActions = ProfileActions(
            onNameChanged = profileViewModel::updateDisplayName,
            onCompleteSetup = profileViewModel::completeSetup,
            onShowMyCard = profileViewModel::showMyCard,
            onShowSettingsScreen = profileViewModel::showSettings,
            onEditProfile = profileViewModel::editProfile,
            onProfileChanged = profileViewModel::updateProfile,
            onSaveProfile = profileViewModel::saveProfile,
            onCancelProfile = profileViewModel::cancelProfileEdit,
            onShowDiscoverySettings = profileViewModel::showDiscoverySettings,
            onDiscoveryPhoneChanged = profileViewModel::updateDiscoveryPhone,
            onDiscoveryEnabledChanged = profileViewModel::updateDiscoveryEnabled,
            onSaveDiscoverySettings = profileViewModel::saveDiscoverySettings,
            onCancelDiscoverySettings = profileViewModel::cancelDiscoveryEdit,
        ),
        contactsActions = ContactsActions(
            onManageContacts = contactsViewModel::beginManageContacts,
            onContactSearchChanged = contactsViewModel::updateContactSearch,
            onBeginAddContact = contactsViewModel::beginAddContact,
            onOpenContact = contactsViewModel::openContact,
            onScanContact = applicationViewModel::requestContactQr,
            onImportContacts = applicationViewModel::requestContacts,
            onMessageContact = contactsViewModel::messageContact,
            onCheckContactOnline = contactsViewModel::checkContactOnline,
            onContactDraftChanged = contactsViewModel::updateContactDraft,
            onSaveContact = contactsViewModel::saveContact,
            onDeleteContact = contactsViewModel::deleteContact,
            onCloseContactEditor = contactsViewModel::closeContactEditor,
            onOpenChatContactProfile = contactsViewModel::openCurrentChatProfile,
            onSaveCurrentChatContact = contactsViewModel::saveCurrentChatContact,
            onCloseChatContactProfile = contactsViewModel::closeCurrentChatProfile,
            onSelectOnlineAccount = contactsViewModel::selectOnlineAccount,
            onCancelAccountSelection = contactsViewModel::cancelAccountSelection,
        ),
        chatActions = ChatActions(
            onStartChat = applicationViewModel::requestNearby,
            onStopChat = viewModel::stopChat,
            onConnect = viewModel::connectToDevice,
            onOpenConversation = viewModel::openConversation,
            onBackToChats = viewModel::showConversationList,
            onConversationSearchChanged = viewModel::updateConversationSearch,
            onMessageDraftChanged = viewModel::updateMessageDraft,
            onSendMessage = viewModel::sendMessage,
            onSendReply = viewModel::sendReply,
            onSendVoice = viewModel::sendVoice,
            onEditMessage = viewModel::editMessage,
            onDeleteMessage = viewModel::deleteMessage,
            onCreatePoll = viewModel::createPoll,
            onVoteInPoll = viewModel::voteInPoll,
            onDisconnect = viewModel::disconnect,
            onDismissError = viewModel::dismissError,
        ),
        eventActions = EventActions(
            onBeginCreate = eventViewModel::beginCreateEvent,
            onBeginEdit = eventViewModel::beginEditEvent,
            onCreate = eventViewModel::createEvent,
            onOpen = eventViewModel::openEvent,
            onUpdate = eventViewModel::updateSelectedEvent,
            onDeleteEvent = eventViewModel::deleteSelectedEvent,
            onJoin = eventViewModel::joinSelectedEvent,
            onInvite = eventViewModel::inviteToSelectedEvent,
            onSearchParticipant = eventViewModel::searchSelectedEventParticipant,
            onAcceptInvitation = eventViewModel::acceptEventInvitation,
            onDeclineInvitation = eventViewModel::declineEventInvitation,
            onRevokeInvitation = eventViewModel::revokeEventInvitation,
            onLeave = eventViewModel::leaveSelectedEvent,
            onPromoteMember = eventViewModel::promoteEventMember,
            onRemoveMember = eventViewModel::removeEventMember,
            onShowAnnouncements = eventViewModel::showEventAnnouncements,
            onPublishAnnouncement = eventViewModel::publishEventAnnouncement,
            onShowDiscussion = eventViewModel::showEventDiscussion,
            onLoadMoreDiscussion = eventViewModel::loadMoreEventDiscussion,
            onOpenDiscussionThread = eventViewModel::openEventDiscussionThread,
            onCreateDiscussionComment = eventViewModel::createEventDiscussionComment,
            onToggleDiscussionLike = eventViewModel::toggleEventDiscussionLike,
            onDeleteDiscussionComment = eventViewModel::deleteEventDiscussionComment,
            onRequestGpsEntry = applicationViewModel::requestEventGps,
            onRequestAdminAccess = applicationViewModel::requestEventAdmin,
            onApproveAdminAccess = eventViewModel::approveEventAdminAccess,
            onScanCheckInQr = applicationViewModel::requestEventQr,
            onShowCheckInQr = eventViewModel::showEventCheckInQr,
            onHideCheckInQr = eventViewModel::hideEventCheckInQr,
            onShowSavedChat = eventViewModel::showSavedEventChat,
            onSendChat = eventViewModel::sendEventMessage,
            onBack = applicationViewModel::handleBack,
        ),
        authActions = AuthActions(
            onCreateEmailAccount = authViewModel::createEmailAccount,
            onSignInWithEmail = authViewModel::signInWithEmail,
            onSignInWithGoogle = applicationViewModel::requestGoogle,
            onRegisterEmail = authViewModel::registerEmail,
            onCompleteAccountProfile = authViewModel::completeAccountProfile,
            onRetryAccountProfile = authViewModel::loadAccountProfile,
            onSignOut = authViewModel::signOut,
        ),
        onCheckVenue = applicationViewModel::requestVenue,
        microphonePermissionGranted = applicationState.permissions.microphone,
        onRequestMicrophonePermission = applicationViewModel::requestMicrophone,
        notificationSettings = applicationState.notifications,
        notificationPermissionGranted = applicationState.permissions.notifications,
        onNotificationSettingsChanged = applicationViewModel::updateNotificationSettings,
        onRequestNotificationPermission = applicationViewModel::requestNotifications,
        onShowEvents = eventViewModel::showEvents,
        getCurrentLocation = applicationViewModel::currentLocation,
        searchPlaces = applicationViewModel::searchPlaces,
        onSystemBack = applicationViewModel::handleBack,
        onDismissEventMessage = eventViewModel::dismissEventMessage,
        onOpenSettings = applicationViewModel::requestSettings,
    )
}
