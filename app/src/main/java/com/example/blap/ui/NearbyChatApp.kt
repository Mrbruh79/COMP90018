package com.example.blap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import com.example.blap.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.ChatUiState
import com.example.blap.chat.ContactProfile
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventUiState
import com.example.blap.location.LocationFix
import com.example.blap.location.PlaceSearchResult
import com.example.blap.ui.screens.profile.ProfileEditorScreen
import com.example.blap.ui.screens.profile.MyCardScreen
import com.example.blap.ui.screens.contacts.ContactsScreen
import com.example.blap.ui.screens.contacts.ContactEditorScreen
import com.example.blap.ui.screens.contacts.ChatContactProfileScreen
import com.example.blap.ui.screens.auth.AccountGate
import com.example.blap.ui.screens.auth.WelcomeScreen
import com.example.blap.ui.screens.settings.SettingsScreen
import com.example.blap.ui.screens.settings.DiscoverySettingsScreen
import com.example.blap.ui.screens.messages.ConversationList
import com.example.blap.ui.screens.messages.ConnectingScreen
import com.example.blap.ui.screens.messages.EmptyChat
import com.example.blap.ui.screens.chat.ChatScreen
import com.example.blap.ui.screens.groups.CreateGroupScreen
import com.example.blap.ui.screens.events.EventHub
import com.example.blap.ui.screens.auth.AuthActions
import com.example.blap.ui.screens.groups.GroupsActions
import com.example.blap.ui.screens.profile.ProfileActions
import com.example.blap.ui.screens.contacts.ContactsActions
import com.example.blap.ui.screens.chat.ChatActions
import com.example.blap.ui.screens.events.EventActions

@Composable
fun NearbyChatApp(
    uiState: ChatUiState,
    eventUiState: EventUiState,
    deniedPermissions: List<String>,
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile?,
    accountProfileLoading: Boolean,
    authActions: AuthActions,
    eventActions: EventActions,
    chatActions: ChatActions,
    contactsActions: ContactsActions,
    profileActions: ProfileActions,
    onCheckVenue: () -> Unit,
    microphonePermissionGranted: Boolean,
    onRequestMicrophonePermission: () -> Unit,
    groupsActions: GroupsActions,
    notificationSettings: ChatNotificationSettings,
    notificationPermissionGranted: Boolean,
    onNotificationSettingsChanged: (ChatNotificationSettings) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onShowEvents: () -> Unit,
    getCurrentLocation: suspend () -> LocationFix?,
    searchPlaces: suspend (String) -> List<PlaceSearchResult>,
    onSystemBack: () -> Unit,
    onDismissEventMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(
        enabled = uiState.screen != ChatScreen.WELCOME && uiState.screen != ChatScreen.CHATS,
        onBack = { if (uiState.screen == ChatScreen.EVENTS) eventActions.onBack() else onSystemBack() },
    )
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error, uiState.notice, eventUiState.error, eventUiState.notice) {
        val message = uiState.error ?: uiState.notice ?: eventUiState.error ?: eventUiState.notice
            ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        chatActions.onDismissError()
        onDismissEventMessage()
    }

    if (!authAccount.isAnonymous && (authAccount.uid.isBlank() || accountProfile == null)) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            AccountGate(
                modifier = Modifier.padding(padding),
                signedIn = authAccount.uid.isNotBlank(),
                loading = accountProfileLoading,
                actions = authActions,
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
                        onClick = contactsActions.onManageContacts,
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
                        onChats = chatActions.onBackToChats,
                        onEvents = onShowEvents,
                        onContacts = contactsActions.onManageContacts,
                        onMyCard = profileActions.onShowMyCard,
                        onSettings = profileActions.onShowSettingsScreen,
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
                        actions = authActions,
                        name = uiState.displayName,
                        nameError = uiState.nameError,
                        deniedPermissions = deniedPermissions,
                        onNameChanged = profileActions.onNameChanged,
                        onStart = profileActions.onCompleteSetup,
                        onOpenSettings = onOpenSettings,
                    )

                    ChatScreen.CHATS -> ConversationList(
                        conversations = uiState.conversations,
                        devices = uiState.discoveredDevices,
                        onOpenConversation = chatActions.onOpenConversation,
                        onConnect = chatActions.onConnect,
                        onManageContacts = contactsActions.onManageContacts,
                        search = uiState.conversationSearch,
                        onSearchChanged = chatActions.onConversationSearchChanged,
                        nearbyActive = uiState.nearbyActive,
                        connectionCount = uiState.directConnectionCount,
                        deniedPermissions = deniedPermissions,
                        onStartNearby = chatActions.onStartChat,
                        onStopNearby = chatActions.onStopChat,
                        onOpenSettings = onOpenSettings,
                        showAccountPrompt = !authAccount.hasPassword && !authAccount.hasGoogle,
                        onOpenAccount = profileActions.onShowSettingsScreen,
                    )

                    ChatScreen.CONNECTING -> ConnectingScreen(
                        authenticationDigits = uiState.authenticationDigits,
                        onBack = chatActions.onBackToChats,
                    )

                    ChatScreen.CONVERSATION -> {
                        val conversation = uiState.conversations.firstOrNull {
                            it.peerId == uiState.selectedPeerId
                        }
                        if (conversation == null) {
                            EmptyChat(chatActions.onBackToChats)
                        } else {
                            ChatScreen(
                                conversation = conversation,
                                messages = uiState.messages,
                                directConnectionCount = uiState.directConnectionCount,
                                onSend = chatActions.onSendMessage,
                                onReply = chatActions.onSendReply,
                                onCreatePoll = chatActions.onCreatePoll,
                                onVote = chatActions.onVoteInPoll,
                                onEdit = chatActions.onEditMessage,
                                onDelete = chatActions.onDeleteMessage,
                                onSendVoice = chatActions.onSendVoice,
                                microphonePermissionGranted = microphonePermissionGranted,
                                onRequestMicrophonePermission = onRequestMicrophonePermission,
                                onOpenContactProfile = contactsActions.onOpenChatContactProfile,
                                draft = uiState.messageDrafts[conversation.peerId].orEmpty(),
                                onDraftChanged = chatActions.onMessageDraftChanged,
                                onBack = chatActions.onBackToChats,
                                onDisconnect = { chatActions.onDisconnect(conversation.peerId) },
                                onOpenGroupSettings = groupsActions.onBeginGroupSettings,
                            )
                        }
                    }

                    ChatScreen.CREATING_GROUP -> CreateGroupScreen(
                        name = uiState.groupNameDraft,
                        contacts = uiState.groupContacts,
                        selectedIds = uiState.selectedGroupMemberIds,
                        onNameChanged = groupsActions.onGroupNameChanged,
                        onToggleMember = groupsActions.onToggleGroupMember,
                        onCreate = groupsActions.onCreateGroup,
                        onBack = onSystemBack,
                    )

                    ChatScreen.MANAGING_CONTACTS -> ContactsScreen(
                        contacts = uiState.savedContacts,
                        onlineReady = authAccount.uid.isNotBlank(),
                        search = uiState.contactSearch,
                        onSearchChanged = contactsActions.onContactSearchChanged,
                        onAdd = contactsActions.onBeginAddContact,
                        onOpen = contactsActions.onOpenContact,
                        onScan = contactsActions.onScanContact,
                        onImport = contactsActions.onImportContacts,
                        onMessage = contactsActions.onMessageContact,
                        onCheckOnline = contactsActions.onCheckContactOnline,
                        onOpenCreateGroup = groupsActions.onOpenCreateGroup,
                        onDiscoverNearby = chatActions.onBackToChats,
                    )

                    ChatScreen.EDITING_CONTACT -> ContactEditorScreen(
                        profile = uiState.contactDraftProfile(),
                        source = uiState.contactSourceDraft,
                        isExisting = uiState.selectedContactId != null,
                        onChanged = contactsActions.onContactDraftChanged,
                        onSave = contactsActions.onSaveContact,
                        onDelete = contactsActions.onDeleteContact,
                        onBack = contactsActions.onCloseContactEditor,
                    )

                    ChatScreen.CONTACT_PROFILE -> ChatContactProfileScreen(
                        conversation = uiState.conversations.firstOrNull { it.peerId == uiState.selectedPeerId },
                        contact = uiState.savedContacts.firstOrNull { it.id == uiState.selectedContactId },
                        onEdit = { uiState.selectedContactId?.let(contactsActions.onOpenContact) },
                        onSaveContact = contactsActions.onSaveCurrentChatContact,
                        onBack = contactsActions.onCloseChatContactProfile,
                    )

                    ChatScreen.SHOWING_MY_CARD -> MyCardScreen(
                        profile = uiState.profile().copy(username = visibleAccountProfile.username),
                        peerId = uiState.myPeerId,
                        onEdit = profileActions.onEditProfile,
                    )

                    ChatScreen.EDITING_PROFILE -> ProfileEditorScreen(
                        profile = uiState.profileDraft ?: uiState.profile(),
                        onChanged = profileActions.onProfileChanged,
                        onSave = profileActions.onSaveProfile,
                        onBack = profileActions.onCancelProfile,
                    )

                    ChatScreen.SETTINGS -> SettingsScreen(
                        authAccount = authAccount,
                        accountProfile = visibleAccountProfile.copy(displayName = uiState.displayName),
                        authActions = authActions,
                        contactCount = uiState.savedContacts.size,
                        connectionCount = uiState.directConnectionCount,
                        onEditProfile = profileActions.onEditProfile,
                        onShowDiscoverySettings = profileActions.onShowDiscoverySettings,
                        onOpenAppSettings = onOpenSettings,
                        nearbyActive = uiState.nearbyActive,
                        onStartNearby = chatActions.onStartChat,
                        onStopNearby = chatActions.onStopChat,
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
                        onPhoneChanged = profileActions.onDiscoveryPhoneChanged,
                        onEnabledChanged = profileActions.onDiscoveryEnabledChanged,
                        onSave = profileActions.onSaveDiscoverySettings,
                        onBack = profileActions.onCancelDiscoverySettings,
                    )

                    ChatScreen.EVENTS -> EventHub(
                        state = eventUiState,
                        actions = eventActions,
                        getCurrentLocation = getCurrentLocation,
                        searchPlaces = searchPlaces,
                    )

                    ChatScreen.GROUP_SETTINGS -> CreateGroupScreen(
                        name = uiState.groupNameDraft,
                        contacts = uiState.groupContacts,
                        selectedIds = uiState.selectedGroupMemberIds,
                        onNameChanged = groupsActions.onGroupNameChanged,
                        onToggleMember = groupsActions.onToggleGroupMember,
                        onCreate = groupsActions.onSaveGroupSettings,
                        onBack = { uiState.selectedPeerId?.let(chatActions.onOpenConversation) ?: chatActions.onBackToChats() },
                        title = "Group settings",
                        actionLabel = "Save changes",
                        editable = uiState.canEditGroup,
                    )

                    ChatScreen.ERROR -> ErrorScreen(chatActions.onStartChat)
                }
                }
            }
        }
        if (uiState.accountCandidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = contactsActions.onCancelAccountSelection,
                title = { Text("Choose the right account") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("These accounts match the contact details. Check the username before linking.")
                        uiState.accountCandidates.forEach { account ->
                            OutlinedButton(
                                onClick = { contactsActions.onSelectOnlineAccount(account.uid) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${account.name}  @${account.username.ifBlank { "unknown" }}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = contactsActions.onCancelAccountSelection) { Text("Cancel") } },
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
