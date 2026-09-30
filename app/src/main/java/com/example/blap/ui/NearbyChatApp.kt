package com.example.blap.ui

import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.blap.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.ChatContent
import com.example.blap.chat.ChatTimeline
import com.example.blap.chat.ChatViewModel
import com.example.blap.chat.ChatUiState
import com.example.blap.chat.ContactProfile
import com.example.blap.chat.ConversationSummary
import com.example.blap.chat.ConversationType
import com.example.blap.chat.GroupContact
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.NearbyDevice
import com.example.blap.chat.VoiceNoteRecorder
import com.example.blap.ui.theme.ButtonHeightExtraSmall
import com.example.blap.ui.theme.ButtonHeightMedium
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventUiState
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.blap.ui.components.Avatar
import com.example.blap.ui.components.DeleteConfirmationDialog
import com.example.blap.ui.components.MessageBubble
import com.example.blap.ui.components.MessageComposer
import com.example.blap.ui.screens.profile.ProfileEditorScreen
import com.example.blap.ui.screens.profile.MyCardScreen
import com.example.blap.ui.screens.contacts.ContactsScreen
import com.example.blap.ui.screens.contacts.ContactEditorScreen
import com.example.blap.ui.screens.contacts.ChatContactProfileScreen
import com.example.blap.ui.screens.auth.AccountGate
import com.example.blap.ui.screens.auth.WelcomeScreen
import com.example.blap.ui.screens.settings.SettingsScreen
import com.example.blap.ui.screens.settings.DiscoverySettingsScreen

@Composable
fun NearbyChatApp(
    uiState: ChatUiState,
    eventUiState: EventUiState,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile?,
    accountProfileLoading: Boolean,
    onRegisterEmail: (String, String, String, String) -> Unit,
    onCompleteAccountProfile: (String, String) -> Unit,
    onRetryAccountProfile: () -> Unit,
    onSignOut: () -> Unit,
    onCreateEmailAccount: (String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
    onStartChat: () -> Unit,
    onCompleteSetup: () -> Unit,
    onStopChat: () -> Unit,
    onCheckVenue: () -> Unit,
    onConnect: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onBackToChats: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendReply: (String, String) -> Unit,
    onCreatePoll: (String, List<String>) -> Unit,
    onVoteInPoll: (String, Int) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onSendVoice: (Int, ByteArray) -> Unit,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    onOpenChatContactProfile: () -> Unit,
    onCloseChatContactProfile: () -> Unit,
    onSaveCurrentChatContact: () -> Unit,
    onMessageDraftChanged: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onOpenCreateGroup: () -> Unit,
    onGroupNameChanged: (String) -> Unit,
    onToggleGroupMember: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onManageContacts: () -> Unit,
    onBeginAddContact: () -> Unit,
    onOpenContact: (String) -> Unit,
    onCloseContactEditor: () -> Unit,
    onMessageContact: (String) -> Unit,
    onCheckContactOnline: (String) -> Unit,
    onSelectOnlineAccount: (String) -> Unit,
    onCancelAccountSelection: () -> Unit,
    onContactDraftChanged: (ContactProfile) -> Unit,
    onDeleteContact: () -> Unit,
    onScanContact: () -> Unit,
    onSaveContact: () -> Unit,
    onImportContacts: () -> Unit,
    onShowMyCard: () -> Unit,
    onEditProfile: () -> Unit,
    onProfileChanged: (ContactProfile) -> Unit,
    onSaveProfile: () -> Unit,
    onCancelProfile: () -> Unit,
    onShowSettingsScreen: () -> Unit,
    notificationSettings: ChatNotificationSettings,
    notificationPermissionGranted: Boolean,
    onNotificationSettingsChanged: (ChatNotificationSettings) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onShowDiscoverySettings: () -> Unit,
    onDiscoveryPhoneChanged: (String) -> Unit,
    onDiscoveryEnabledChanged: (Boolean) -> Unit,
    onSaveDiscoverySettings: () -> Unit,
    onCancelDiscoverySettings: () -> Unit,
    onShowEvents: () -> Unit,
    onBeginCreateEvent: () -> Unit,
    onBeginEditEvent: () -> Unit,
    onCreateEvent: (EventCreateRequest) -> Unit,
    onOpenEvent: (String) -> Unit,
    onUpdateEvent: (EventCreateRequest) -> Unit,
    onDeleteEvent: () -> Unit,
    onJoinEvent: () -> Unit,
    onInviteToEvent: (String) -> Unit,
    onSearchEventParticipant: (String) -> Unit,
    onAcceptEventInvitation: (String) -> Unit,
    onDeclineEventInvitation: (String) -> Unit,
    onRevokeEventInvitation: (String) -> Unit,
    onLeaveEvent: () -> Unit,
    onPromoteEventMember: (String) -> Unit,
    onRemoveEventMember: (String) -> Unit,
    onDeleteEventData: () -> Unit,
    onShowEventAnnouncements: () -> Unit,
    onPublishEventAnnouncement: (String) -> Unit,
    onShowEventDiscussion: () -> Unit,
    onLoadMoreEventDiscussion: () -> Unit,
    onOpenEventDiscussionThread: (String) -> Unit,
    onCreateEventDiscussionComment: (String, String?) -> Unit,
    onToggleEventDiscussionLike: (String) -> Unit,
    onDeleteEventDiscussionComment: (String) -> Unit,
    onRequestEventGpsEntry: () -> Unit,
    onRequestEventAdminAccess: () -> Unit,
    onApproveEventAdminAccess: (String) -> Unit,
    onScanEventQr: () -> Unit,
    onShowEventQr: () -> Unit,
    onHideEventQr: () -> Unit,
    onShowSavedEventChat: () -> Unit,
    onSendEventMessage: (String) -> Unit,
    onEventBack: () -> Unit,
    onConversationSearchChanged: (String) -> Unit,
    onContactSearchChanged: (String) -> Unit,
    onBeginGroupSettings: () -> Unit,
    onSaveGroupSettings: () -> Unit,
    onSystemBack: () -> Unit,
    onDismissError: () -> Unit,
    onDismissEventMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(
        enabled = uiState.screen != ChatScreen.WELCOME && uiState.screen != ChatScreen.CHATS,
        onBack = { if (uiState.screen == ChatScreen.EVENTS) onEventBack() else onSystemBack() },
    )
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error, uiState.notice, eventUiState.error, eventUiState.notice) {
        val message = uiState.error ?: uiState.notice ?: eventUiState.error ?: eventUiState.notice
            ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissError()
        onDismissEventMessage()
    }

    if (!authAccount.isAnonymous && (authAccount.uid.isBlank() || accountProfile == null)) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            AccountGate(
                modifier = Modifier.padding(padding),
                signedIn = authAccount.uid.isNotBlank(),
                loading = accountProfileLoading,
                onRegisterEmail = onRegisterEmail,
                onSignInWithEmail = onSignInWithEmail,
                onSignInWithGoogle = onSignInWithGoogle,
                onCompleteProfile = onCompleteAccountProfile,
                onRetry = onRetryAccountProfile,
                onSignOut = onSignOut,
            )
        }
        return
    }
    val visibleAccountProfile = accountProfile
        ?: PublicAccountProfile(username = "", displayName = uiState.displayName)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            // contentColorFor(Color.Transparent) can't match a theme color and falls back to
            // Color.Unspecified (renders as black) — set it explicitly so text placed directly
            // in the Scaffold (not inside its own Card/Surface) still inherits a real color.
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (uiState.screen == ChatScreen.CHATS) {
                    FloatingActionButton(
                        onClick = onManageContacts,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_new_chat),
                            contentDescription = "New chat",
                        )
                    }
                }
            },
            bottomBar = {
                if (uiState.screen in TOP_LEVEL_SCREENS) {
                    AppNavigationBar(
                        state = uiState.screen,
                        onChats = onBackToChats,
                        onEvents = onShowEvents,
                        onContacts = onManageContacts,
                        onMyCard = onShowMyCard,
                        onSettings = onShowSettingsScreen,
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .padding(horizontal = 18.dp),
            ) {
                if (uiState.screen in TOP_LEVEL_SCREENS) Header(uiState)
                Crossfade(
                    targetState = uiState.screen,
                    animationSpec = tween(180),
                    modifier = Modifier.weight(1f),
                    label = "Screen transition",
                ) { screen ->
                when (screen) {
                    ChatScreen.WELCOME -> WelcomeScreen(
                        authAccount = authAccount,
                        onCreateEmailAccount = onCreateEmailAccount,
                        onSignInWithEmail = onSignInWithEmail,
                        onSignInWithGoogle = onSignInWithGoogle,
                        name = uiState.displayName,
                        nameError = uiState.nameError,
                        deniedPermissions = deniedPermissions,
                        onNameChanged = onNameChanged,
                        onStart = onCompleteSetup,
                        onOpenSettings = onOpenSettings,
                    )

                    ChatScreen.CHATS -> ConversationList(
                        conversations = uiState.conversations,
                        devices = uiState.discoveredDevices,
                        onOpenConversation = onOpenConversation,
                        onConnect = onConnect,
                        onManageContacts = onManageContacts,
                        search = uiState.conversationSearch,
                        onSearchChanged = onConversationSearchChanged,
                        nearbyActive = uiState.nearbyActive,
                        connectionCount = uiState.directConnectionCount,
                        deniedPermissions = deniedPermissions,
                        onStartNearby = onStartChat,
                        onStopNearby = onStopChat,
                        onOpenSettings = onOpenSettings,
                        showAccountPrompt = !authAccount.hasPassword && !authAccount.hasGoogle,
                        onOpenAccount = onShowSettingsScreen,
                    )

                    ChatScreen.CONNECTING -> ConnectingScreen(
                        authenticationDigits = uiState.authenticationDigits,
                        onBack = onBackToChats,
                    )

                    ChatScreen.CONVERSATION -> {
                        val conversation = uiState.conversations.firstOrNull {
                            it.peerId == uiState.selectedPeerId
                        }
                        if (conversation == null) {
                            EmptyChat(onBackToChats)
                        } else {
                            ChatScreen(
                                conversation = conversation,
                                messages = uiState.messages,
                                directConnectionCount = uiState.directConnectionCount,
                                onSend = onSendMessage,
                                onReply = onSendReply,
                                onCreatePoll = onCreatePoll,
                                onVote = onVoteInPoll,
                                onEdit = onEditMessage,
                                onDelete = onDeleteMessage,
                                onSendVoice = onSendVoice,
                                microphonePermissionGranted = microphonePermissionGranted,
                                onRequestMicrophonePermission = onRequestMicrophonePermission,
                                onOpenContactProfile = onOpenChatContactProfile,
                                draft = uiState.messageDrafts[conversation.peerId].orEmpty(),
                                onDraftChanged = onMessageDraftChanged,
                                onBack = onBackToChats,
                                onDisconnect = { onDisconnect(conversation.peerId) },
                                onOpenGroupSettings = onBeginGroupSettings,
                            )
                        }
                    }

                    ChatScreen.CREATING_GROUP -> CreateGroupScreen(
                        name = uiState.groupNameDraft,
                        contacts = uiState.groupContacts,
                        selectedIds = uiState.selectedGroupMemberIds,
                        onNameChanged = onGroupNameChanged,
                        onToggleMember = onToggleGroupMember,
                        onCreate = onCreateGroup,
                        onBack = onSystemBack,
                    )

                    ChatScreen.MANAGING_CONTACTS -> ContactsScreen(
                        contacts = uiState.savedContacts,
                        onlineReady = authAccount.uid.isNotBlank(),
                        search = uiState.contactSearch,
                        onSearchChanged = onContactSearchChanged,
                        onAdd = onBeginAddContact,
                        onOpen = onOpenContact,
                        onScan = onScanContact,
                        onImport = onImportContacts,
                        onMessage = onMessageContact,
                        onCheckOnline = onCheckContactOnline,
                        onOpenCreateGroup = onOpenCreateGroup,
                        onDiscoverNearby = onBackToChats,
                    )

                    ChatScreen.EDITING_CONTACT -> ContactEditorScreen(
                        profile = uiState.contactDraftProfile(),
                        source = uiState.contactSourceDraft,
                        isExisting = uiState.selectedContactId != null,
                        onChanged = onContactDraftChanged,
                        onSave = onSaveContact,
                        onDelete = onDeleteContact,
                        onBack = onCloseContactEditor,
                    )

                    ChatScreen.CONTACT_PROFILE -> ChatContactProfileScreen(
                        conversation = uiState.conversations.firstOrNull { it.peerId == uiState.selectedPeerId },
                        contact = uiState.savedContacts.firstOrNull { it.id == uiState.selectedContactId },
                        onEdit = { uiState.selectedContactId?.let(onOpenContact) },
                        onSaveContact = onSaveCurrentChatContact,
                        onBack = onCloseChatContactProfile,
                    )

                    ChatScreen.SHOWING_MY_CARD -> MyCardScreen(
                        profile = uiState.profile().copy(username = visibleAccountProfile.username),
                        peerId = uiState.myPeerId,
                        onEdit = onEditProfile,
                    )

                    ChatScreen.EDITING_PROFILE -> ProfileEditorScreen(
                        profile = uiState.profileDraft ?: uiState.profile(),
                        onChanged = onProfileChanged,
                        onSave = onSaveProfile,
                        onBack = onCancelProfile,
                    )

                    ChatScreen.SETTINGS -> SettingsScreen(
                        authAccount = authAccount,
                        accountProfile = visibleAccountProfile.copy(displayName = uiState.displayName),
                        onSignOut = onSignOut,
                        onCreateEmailAccount = onCreateEmailAccount,
                        onSignInWithEmail = onSignInWithEmail,
                        onSignInWithGoogle = onSignInWithGoogle,
                        contactCount = uiState.savedContacts.size,
                        connectionCount = uiState.directConnectionCount,
                        onEditProfile = onEditProfile,
                        onShowDiscoverySettings = onShowDiscoverySettings,
                        onOpenAppSettings = onOpenSettings,
                        nearbyActive = uiState.nearbyActive,
                        onStartNearby = onStartChat,
                        onStopNearby = onStopChat,
                        venueStatus = uiState.venueStatus,
                        checkingVenue = uiState.checkingVenue,
                        onCheckVenue = onCheckVenue,
                        notificationSettings = notificationSettings,
                        notificationPermissionGranted = notificationPermissionGranted,
                        onNotificationSettingsChanged = onNotificationSettingsChanged,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                    )

                    ChatScreen.DISCOVERY_SETTINGS -> DiscoverySettingsScreen(
                        authAccount = authAccount,
                        accountProfile = visibleAccountProfile,
                        lookupPhoneNumber = (uiState.profileDraft ?: uiState.profile()).lookupPhoneNumber,
                        enabled = (uiState.profileDraft ?: uiState.profile()).discoverableByPhone,
                        savedLookupPhoneNumber = uiState.profileLookupPhoneNumber,
                        savedEnabled = uiState.profileDiscoverableByPhone,
                        onlineLookupStatus = uiState.onlineLookupStatus,
                        onPhoneChanged = onDiscoveryPhoneChanged,
                        onEnabledChanged = onDiscoveryEnabledChanged,
                        onSave = onSaveDiscoverySettings,
                        onBack = onCancelDiscoverySettings,
                    )

                    ChatScreen.EVENTS -> EventHub(
                        state = eventUiState,
                        onBeginCreate = onBeginCreateEvent,
                        onBeginEdit = onBeginEditEvent,
                        onCreate = onCreateEvent,
                        onOpen = onOpenEvent,
                        onUpdate = onUpdateEvent,
                        onDeleteEvent = onDeleteEvent,
                        onJoin = onJoinEvent,
                        onInvite = onInviteToEvent,
                        onSearchParticipant = onSearchEventParticipant,
                        onAcceptInvitation = onAcceptEventInvitation,
                        onDeclineInvitation = onDeclineEventInvitation,
                        onRevokeInvitation = onRevokeEventInvitation,
                        onLeave = onLeaveEvent,
                        onPromoteMember = onPromoteEventMember,
                        onRemoveMember = onRemoveEventMember,
                        onDeleteLocalData = onDeleteEventData,
                        onShowAnnouncements = onShowEventAnnouncements,
                        onPublishAnnouncement = onPublishEventAnnouncement,
                        onShowDiscussion = onShowEventDiscussion,
                        onLoadMoreDiscussion = onLoadMoreEventDiscussion,
                        onOpenDiscussionThread = onOpenEventDiscussionThread,
                        onCreateDiscussionComment = onCreateEventDiscussionComment,
                        onToggleDiscussionLike = onToggleEventDiscussionLike,
                        onDeleteDiscussionComment = onDeleteEventDiscussionComment,
                        onRequestGpsEntry = onRequestEventGpsEntry,
                        onRequestAdminAccess = onRequestEventAdminAccess,
                        onApproveAdminAccess = onApproveEventAdminAccess,
                        onScanCheckInQr = onScanEventQr,
                        onShowCheckInQr = onShowEventQr,
                        onHideCheckInQr = onHideEventQr,
                        onShowSavedChat = onShowSavedEventChat,
                        onSendChat = onSendEventMessage,
                        onBack = onEventBack,
                    )

                    ChatScreen.GROUP_SETTINGS -> CreateGroupScreen(
                        name = uiState.groupNameDraft,
                        contacts = uiState.groupContacts,
                        selectedIds = uiState.selectedGroupMemberIds,
                        onNameChanged = onGroupNameChanged,
                        onToggleMember = onToggleGroupMember,
                        onCreate = onSaveGroupSettings,
                        onBack = { uiState.selectedPeerId?.let(onOpenConversation) ?: onBackToChats() },
                        title = "Group settings",
                        actionLabel = "Save changes",
                        editable = uiState.canEditGroup,
                    )

                    ChatScreen.ERROR -> ErrorScreen(onStartChat)
                }
                }
            }
        }
        if (uiState.accountCandidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = onCancelAccountSelection,
                title = { Text("Choose the right account") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("These accounts match the contact details. Check the username before linking.")
                        uiState.accountCandidates.forEach { account ->
                            OutlinedButton(
                                onClick = { onSelectOnlineAccount(account.uid) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${account.name}  @${account.username.ifBlank { "unknown" }}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onCancelAccountSelection) { Text("Cancel") } },
            )
        }
    }
}

private val TOP_LEVEL_TITLES = mapOf(
    ChatScreen.CHATS to "Messages",
    ChatScreen.MANAGING_CONTACTS to "Contacts",
    ChatScreen.EVENTS to "Events",
    ChatScreen.SHOWING_MY_CARD to "Profile Card",
    ChatScreen.SETTINGS to "Settings",
)

private val TOP_LEVEL_SCREENS = TOP_LEVEL_TITLES.keys

private fun ChatUiState.profile() = ContactProfile(
    displayName = displayName,
    phoneNumber = phoneNumber,
    email = profileEmail,
    googleAccountEmail = profileGoogleEmail,
    discoverableByPhone = profileDiscoverableByPhone,
    lookupPhoneNumber = profileLookupPhoneNumber,
    bio = profileBio,
    websiteUrl = profileWebsite,
    instagramUrl = profileInstagram,
    xUrl = profileX,
    linkedinUrl = profileLinkedin,
    githubUrl = profileGithub,
)

private fun ChatUiState.contactDraftProfile() = ContactProfile(
    displayName = contactNameDraft,
    phoneNumber = contactPhoneDraft,
    email = contactEmailDraft,
    googleAccountEmail = contactGoogleEmailDraft,
    username = contactUsernameDraft,
    bio = contactBioDraft,
    websiteUrl = contactWebsiteDraft,
    instagramUrl = contactInstagramDraft,
    xUrl = contactXDraft,
    linkedinUrl = contactLinkedinDraft,
    githubUrl = contactGithubDraft,
)

@Composable
private fun AppNavigationBar(
    state: ChatScreen,
    onChats: () -> Unit,
    onEvents: () -> Unit,
    onContacts: () -> Unit,
    onMyCard: () -> Unit,
    onSettings: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        NavigationBarItem(
            selected = state == ChatScreen.CHATS,
            onClick = onChats,
            icon = { Icon(painterResource(R.drawable.ic_chat), contentDescription = null) },
            label = { Text("Messages") },
        )
        NavigationBarItem(
            selected = state == ChatScreen.MANAGING_CONTACTS,
            onClick = onContacts,
            icon = { Icon(painterResource(R.drawable.ic_contacts), contentDescription = null) },
            label = { Text("Contacts") },
        )
        NavigationBarItem(
            selected = state == ChatScreen.EVENTS,
            onClick = onEvents,
            icon = { Icon(painterResource(R.drawable.ic_chat), contentDescription = null) },
            label = { Text("Events") },
        )
        NavigationBarItem(
            selected = state == ChatScreen.SHOWING_MY_CARD,
            onClick = onMyCard,
            icon = { Icon(painterResource(R.drawable.ic_qr), contentDescription = null) },
            label = { Text("Profile Card") },
        )
        NavigationBarItem(
            selected = state == ChatScreen.SETTINGS,
            onClick = onSettings,
            icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

@Composable
private fun Header(state: ChatUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            TOP_LEVEL_TITLES[state.screen].orEmpty(),
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        NearbyPill(state)
    }
}

@Composable
private fun NearbyPill(state: ChatUiState) {
    val connectedCount = state.directConnectionCount
    val text = when {
        !state.nearbyActive -> "Nearby Off"
        connectedCount == 0 -> "Nearby Active"
        else -> "$connectedCount connected"
    }
    Text(
        text,
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (state.nearbyActive) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                },
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        color = if (state.nearbyActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    devices: List<NearbyDevice>,
    onOpenConversation: (String) -> Unit,
    onConnect: (String) -> Unit,
    onManageContacts: () -> Unit,
    search: String,
    onSearchChanged: (String) -> Unit,
    nearbyActive: Boolean,
    connectionCount: Int,
    deniedPermissions: List<String>,
    onStartNearby: () -> Unit,
    onStopNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    showAccountPrompt: Boolean,
    onOpenAccount: () -> Unit,
) {
    val searching = search.isNotBlank()
    val searchResults = conversations.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.lastMessage.contains(search, ignoreCase = true)
    }
    val nearbyChat = conversations.firstOrNull { it.type == ConversationType.OPEN_MESH }
    val recentChats = conversations.filter { it.type != ConversationType.OPEN_MESH }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (showAccountPrompt && !searching) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccount),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Sign in to your account to enable online chats and contacts sync.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text("Search messages") },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                },
                shape = CircleShape,
            )
        }
        if (searching) {
            item { SectionHeader("Search Result") }
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        "No conversations match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(searchResults, key = ConversationSummary::peerId) { conversation ->
                ConversationCard(conversation) { onOpenConversation(conversation.peerId) }
            }
        } else {
            if (recentChats.isNotEmpty()) {
                item { SectionHeader("Recent Chats") }
                items(recentChats, key = ConversationSummary::peerId) { conversation ->
                    ConversationCard(conversation) { onOpenConversation(conversation.peerId) }
                }
            }
            if (nearbyChat != null) {
                item {
                    SectionHeader("Nearby Chat", "Chat with nearby CommonGround users.")
                }
                item {
                    ConversationCard(nearbyChat) { onOpenConversation(nearbyChat.peerId) }
                }
                item {
                    Text(
                        "Your information will not be shared until you connect with someone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionHeader(
                    "Discover Nearby Contacts",
                    "Discover and connect with other CommonGround users.",
                )
                if (nearbyActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                if (connectionCount > 0) {
                                    "$connectionCount phone${if (connectionCount == 1) "" else "s"} connected"
                                } else {
                                    "Nearby search is currently active"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "Your account is discoverable to nearby devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            NearbyToggleButton(
                                label = "Turn off nearby",
                                filled = true,
                                onClick = onStopNearby,
                            )
                        }
                    }
                } else {
                    NearbyToggleButton(
                        label = "Turn on nearby",
                        filled = false,
                        onClick = onStartNearby,
                    )
                }
                if (deniedPermissions.isNotEmpty()) {
                    Text(
                        "Permissions must be enabled to be able to use the nearby feature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .height(ButtonHeightExtraSmall),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Open permissions", style = MaterialTheme.typography.titleSmall) }
                }
            }
            if (nearbyActive) {
                items(devices, key = NearbyDevice::endpointId) { device ->
                    DeviceRow(device) { onConnect(device.endpointId) }
                }
                item {
                    Text(
                        if (devices.isEmpty()) {
                            "Devices you can connect with will be shown here."
                        } else {
                            "${devices.size} device${if (devices.size == 1) "" else "s"} ready to connect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyToggleButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            painterResource(R.drawable.ic_bluetooth),
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
    val modifier = Modifier
        .fillMaxWidth()
        .padding(top = 16.dp)
        .height(ButtonHeightMedium)
    if (filled) {
        Button(onClick = onClick, modifier = modifier, content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            content = content,
        )
    }
}

@Composable
private fun DeviceRow(device: NearbyDevice, onConnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(device.name, connected = true)
        Text(
            device.name,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Button(
            onClick = onConnect,
            modifier = Modifier.semantics {
                contentDescription = "Connect to ${device.name}"
            },
        ) { Text("Connect") }
    }
}

/**
 * A conversation is reachable when a message sent now can actually arrive: over the nearby mesh,
 * or via a linked online account. Mesh chat has no cloud path, so it reduces to [connected].
 */
private fun ConversationSummary.reachable() = connected || onlineAccountLinked

private fun ConversationSummary.emptyPreview() = when (type) {
    ConversationType.OPEN_MESH -> "Discover connections with public nearby chat."
    else -> "Say hello"
}

@DrawableRes
private fun ConversationSummary.avatarIcon(): Int? = when (type) {
    ConversationType.OPEN_MESH -> R.drawable.ic_nearby_chat
    ConversationType.PRIVATE_GROUP -> R.drawable.ic_contacts
    ConversationType.DIRECT -> null
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ConversationCard(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(conversation.name, conversation.reachable(), conversation.avatarIcon())
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(conversation.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conversation.lastMessageAt > 0 && conversation.lastMessage.isNotBlank()) {
                        Text(formatConversationTime(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(conversation.lastMessage.ifBlank { conversation.emptyPreview() }, maxLines = 2,
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun formatConversationTime(time: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val pattern = if (today.format(Date(time)) == today.format(Date())) "h:mm a" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
}

@Composable
private fun ConnectingScreen(authenticationDigits: String?, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connecting phones", style = MaterialTheme.typography.headlineMedium)
        Text("Keep both phones nearby", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (authenticationDigits != null) {
            Card(
                modifier = Modifier.padding(top = 22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("CHECK BOTH PHONES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(authenticationDigits, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
    }
}

@Composable
private fun CreateGroupScreen(
    name: String,
    contacts: List<GroupContact>,
    selectedIds: Set<String>,
    onNameChanged: (String) -> Unit,
    onToggleMember: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    title: String = "Create private group",
    actionLabel: String = "Create group",
    editable: Boolean = true,
    onDelete: (() -> Unit)? = null,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (editable) "Choose the people you want in this group." else "Only the group owner can change the name or members.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = name,
            readOnly = !editable,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Group name") },
            shape = RoundedCornerShape(15.dp),
        )
        Text(
            "Members",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (contacts.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            "Add or import contacts from the Contacts tab, then return here to create your group.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(if (editable) contacts else contacts.filter { it.peerId in selectedIds }, key = GroupContact::peerId) { contact ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = editable) { onToggleMember(contact.peerId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = contact.peerId in selectedIds,
                                enabled = editable,
                                onCheckedChange = { onToggleMember(contact.peerId) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        contact.connected -> "Connected now"
                                        contact.availableOnMesh -> "Recognized on the mesh"
                                        contact.username.isNotBlank() -> "Online address: @${contact.username}"
                                        contact.phoneNumber.isNotBlank() -> "Will match by phone number when they join"
                                        else -> "Saved by email. Pair nearby for mesh chat"
                                    },
                                    color = if (contact.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (editable) Button(
            onClick = onCreate,
            enabled = name.isNotBlank() && selectedIds.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .height(52.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Text("$actionLabel (${selectedIds.size + 1})")
        }
        if (onDelete != null) {
            TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Delete group", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmingDelete && onDelete != null) {
        DeleteConfirmationDialog(
            title = "Delete this group?",
            message = "The group and its messages will be removed from this phone.",
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun ChatScreen(
    conversation: ConversationSummary,
    messages: List<ChatMessage>,
    directConnectionCount: Int,
    onSend: (String) -> Unit,
    onReply: (String, String) -> Unit,
    onCreatePoll: (String, List<String>) -> Unit,
    onVote: (String, Int) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSendVoice: (Int, ByteArray) -> Unit,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    onOpenContactProfile: () -> Unit,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenGroupSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val presented = remember(messages) { ChatTimeline.present(messages) }
    var replyingTo by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var editingText by rememberSaveable(conversation.peerId) { mutableStateOf("") }
    var deletingId by rememberSaveable(conversation.peerId) { mutableStateOf<String?>(null) }
    var showPollDialog by rememberSaveable(conversation.peerId) { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val recorder = remember { VoiceNoteRecorder(context) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(conversation.peerId) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(messages.lastOrNull()?.id, imeBottom) {
        if (messages.isNotEmpty() &&
            (listState.firstVisibleItemIndex < 2 || messages.last().author == MessageAuthor.ME)
        ) listState.scrollToItem(0)
    }
    DisposableEffect(conversation.peerId) {
        onDispose { recorder.cancel() }
    }
    val finishRecording: (Boolean) -> Unit = { send ->
        recording = false
        val note = if (send) recorder.stop() else {
            recorder.cancel()
            null
        }
        note?.let { onSendVoice(it.first, it.second) }
    }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while (isActive) {
            elapsedMs = (SystemClock.elapsedRealtime() - started).toInt()
            if (elapsedMs >= ChatViewModel.MAX_VOICE_DURATION_MS) {
                finishRecording(true)
                break
            }
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Box(Modifier.clickable(enabled = conversation.type == ConversationType.DIRECT) {
                onOpenContactProfile()
            }) { Avatar(conversation.name, conversation.reachable()) }
            Column(
                Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .clickable(enabled = conversation.type == ConversationType.DIRECT) { onOpenContactProfile() },
            ) {
                Text(conversation.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (conversation.type == ConversationType.DIRECT) {
                    Text("Tap to view profile", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    when {
                        conversation.type == ConversationType.OPEN_MESH && directConnectionCount > 0 ->
                            "$directConnectionCount direct link${if (directConnectionCount == 1) "" else "s"} · relaying through mesh"
                        conversation.type == ConversationType.OPEN_MESH -> "Public room · no direct links"
                        conversation.type == ConversationType.PRIVATE_GROUP && directConnectionCount > 0 ->
                            "${conversation.memberCount} members · relaying through mesh"
                        conversation.type == ConversationType.PRIVATE_GROUP ->
                            "${conversation.memberCount} members · no nearby links"
                        conversation.connected -> "Connected nearby"
                        conversation.onlineAccountLinked -> "Online account linked · not nearby"
                        else -> "Not connected nearby · messages may wait"
                    },
                    color = if (conversation.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (conversation.type != ConversationType.OPEN_MESH) Box {
                TextButton(onClick = { moreExpanded = true }) { Text("More") }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    if (conversation.type == ConversationType.DIRECT) {
                        DropdownMenuItem(text = { Text("Contact profile") }, onClick = {
                            moreExpanded = false
                            onOpenContactProfile()
                        })
                    }
                    if (conversation.type == ConversationType.PRIVATE_GROUP) {
                        DropdownMenuItem(text = { Text("Group settings") }, onClick = {
                            moreExpanded = false
                            onOpenGroupSettings()
                        })
                    }
                    if (conversation.connected && conversation.type == ConversationType.DIRECT) {
                        DropdownMenuItem(text = { Text("Disconnect nearby") }, onClick = {
                            moreExpanded = false
                            onDisconnect()
                        })
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            if (presented.isEmpty()) {
                item { Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(presented.asReversed(), key = { it.message.id }) { item ->
                    MessageBubble(
                        item = item,
                        showSender = conversation.type != ConversationType.DIRECT,
                        onReply = { replyingTo = item.message.id; editingId = null },
                        onEdit = {
                            editingId = item.message.id
                            editingText = when (val content = item.content) {
                                is ChatContent.Text -> content.body
                                is ChatContent.Reply -> content.body
                                else -> ""
                            }
                            replyingTo = null
                        },
                        onDelete = { deletingId = item.message.id },
                        onVote = { option -> onVote(item.message.id, option) },
                    )
                }
            }
        }
        AnimatedVisibility(replyingTo != null || editingId != null) {
            val target = presented.firstOrNull { it.message.id == (editingId ?: replyingTo) }
            Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (editingId != null) "Editing message" else "Replying to ${target?.message?.senderName.orEmpty()}",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { replyingTo = null; editingId = null }) { Text("Cancel") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                Text(
                    "Recording ${elapsedMs / 1000}s",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { finishRecording(false) }) { Text("Cancel") }
                TextButton(onClick = { finishRecording(true) }) { Text("Send") }
            } else {
                TextButton(onClick = { showPollDialog = true }) { Text("Poll") }
                TextButton(onClick = {
                    if (!microphonePermissionGranted) {
                        onRequestMicrophonePermission()
                        return@TextButton
                    }
                    if (recorder.start()) {
                        elapsedMs = 0
                        recording = true
                    }
                }) { Text("Mic") }
                Box(Modifier.weight(1f)) {
                    MessageComposer(
                        text = if (editingId != null) editingText else draft,
                        onTextChanged = { if (editingId != null) editingText = it else onDraftChanged(it) },
                        onSend = { text ->
                            when {
                                editingId != null -> onEdit(requireNotNull(editingId), text)
                                replyingTo != null -> onReply(requireNotNull(replyingTo), text)
                                else -> onSend(text)
                            }
                            replyingTo = null
                            editingId = null
                        },
                        sendLabel = if (editingId != null) "Save" else "Send",
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (deletingId != null) DeleteConfirmationDialog(
        title = "Delete this message?",
        message = "It will disappear for people using the updated app when this change syncs.",
        onConfirm = { onDelete(requireNotNull(deletingId)); deletingId = null },
        onDismiss = { deletingId = null },
    )
    if (showPollDialog) PollComposerDialog(
        onCreate = { question, options -> onCreatePoll(question, options); showPollDialog = false },
        onDismiss = { showPollDialog = false },
    )
}

@Composable
private fun PollComposerDialog(onCreate: (String, List<String>) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    var third by rememberSaveable { mutableStateOf("") }
    var fourth by rememberSaveable { mutableStateOf("") }
    val options = listOf(first, second, third, fourth).map(String::trim).filter(String::isNotBlank)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a poll") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(question, { question = it.take(180) }, label = { Text("Question") })
                OutlinedTextField(first, { first = it.take(80) }, label = { Text("Option 1") })
                OutlinedTextField(second, { second = it.take(80) }, label = { Text("Option 2") })
                OutlinedTextField(third, { third = it.take(80) }, label = { Text("Option 3 (optional)") })
                OutlinedTextField(fourth, { fourth = it.take(80) }, label = { Text("Option 4 (optional)") })
                Text("Tap an option in the chat to vote. You can change your vote.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(question.trim(), options) },
                enabled = question.isNotBlank() && first.isNotBlank() && second.isNotBlank() &&
                    options.size == options.distinct().size,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyChat(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Conversation not found")
        TextButton(onClick = onBack) { Text("Back to messages") }
    }
}

@Composable
private fun ErrorScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Offline chat could not start", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Try again") }
    }
}
