package com.example.blap.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import com.example.blap.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatMessage
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.ChatContent
import com.example.blap.chat.ChatTimeline
import com.example.blap.chat.PresentedMessage
import com.example.blap.chat.ChatUiState
import com.example.blap.chat.ContactCardCodec
import com.example.blap.chat.ContactProfile
import com.example.blap.chat.ContactSource
import com.example.blap.chat.ConversationSummary
import com.example.blap.chat.ConversationType
import com.example.blap.chat.GroupContact
import com.example.blap.chat.MessageAuthor
import com.example.blap.chat.MessageStatus
import com.example.blap.chat.NearbyDevice
import com.example.blap.chat.ProfileUrl
import com.example.blap.chat.SavedContact
import com.example.blap.chat.PhoneNumberParts
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.event.EventCreateRequest
import com.example.blap.event.EventUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    onOpenChatContactProfile: () -> Unit,
    onCloseChatContactProfile: () -> Unit,
    onSaveCurrentChatContact: () -> Unit,
    onMessageDraftChanged: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onBeginCreateGroup: () -> Unit,
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
                        onBeginCreateGroup = onBeginCreateGroup,
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
                        onBack = onBackToChats,
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
private fun AccountGate(
    modifier: Modifier,
    signedIn: Boolean,
    loading: Boolean,
    onRegisterEmail: (String, String, String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
    onCompleteProfile: (String, String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var creatingAccount by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("CommonGround", style = MaterialTheme.typography.headlineMedium)
        if (signedIn) {
            Text("Set up your profile", style = MaterialTheme.typography.titleLarge)
            Text("Choose a unique username. Your display name can be shared by other people.")
            if (loading) {
                Text("Loading your account...")
            } else {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(20) },
                    label = { Text("Username") },
                    supportingText = { Text("3 to 20 letters, numbers or underscores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(24) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onCompleteProfile(username, displayName) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save and continue")
                }
                TextButton(onClick = onRetry) { Text("Retry loading my profile") }
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        } else {
            Text("Sign in to see your chats and contacts on this phone.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !creatingAccount, onClick = { creatingAccount = false }, label = { Text("Sign in") })
                FilterChip(selected = creatingAccount, onClick = { creatingAccount = true }, label = { Text("Create account") })
            }
            if (creatingAccount) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(20) },
                    label = { Text("Unique username") },
                    supportingText = { Text("3 to 20 letters, numbers or underscores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(24) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(120) },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (creatingAccount) onRegisterEmail(email, password, username, displayName)
                    else onSignInWithEmail(email, password)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (creatingAccount) "Create account" else "Sign in with email") }
            OutlinedButton(onClick = onSignInWithGoogle, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with Google")
            }
            Text(
                "Phone numbers are optional contact details, not a sign-in method.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WelcomeScreen(
    authAccount: AuthAccount,
    onCreateEmailAccount: (String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
    name: String,
    nameError: String?,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text("Join CommonGround", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your profile is ready. Nearby messaging works without an Internet connection.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountAccess(
                authAccount = authAccount,
                onCreateEmailAccount = onCreateEmailAccount,
                onSignInWithEmail = onSignInWithEmail,
                onSignInWithGoogle = onSignInWithGoogle,
            )
            Text("Your nearby profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError != null,
                label = { Text("Display Name") },
                supportingText = nameError?.let { message ->
                    { Text(message) }
                },
                trailingIcon = if (nameError != null) {
                    { Icon(painterResource(R.drawable.ic_error), contentDescription = null) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onStart() }),
            )

            if (deniedPermissions.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                        deniedPermissions.forEach { permission ->
                            Text("• $permission", modifier = Modifier.padding(top = 4.dp))
                        }
                        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) {
                            Text("Open settings")
                        }
                    }
                }
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(56.dp),
        ) {
            Text("Continue to chats")
        }
    }
}

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    devices: List<NearbyDevice>,
    onOpenConversation: (String) -> Unit,
    onConnect: (String) -> Unit,
    onBeginCreateGroup: () -> Unit,
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
    var newChatMenuExpanded by remember { mutableStateOf(false) }
    val filteredConversations = conversations.filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true) ||
            it.lastMessage.contains(search, ignoreCase = true)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        if (showAccountPrompt && search.isBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccount),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Sign in or link an account", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sign in with Email or Google to use online chats.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box {
                    TextButton(onClick = { newChatMenuExpanded = true }) { Text("+ New chat") }
                    DropdownMenu(expanded = newChatMenuExpanded, onDismissRequest = { newChatMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Message a contact") }, onClick = {
                            newChatMenuExpanded = false
                            onManageContacts()
                        })
                        DropdownMenuItem(text = { Text("Create private group") }, onClick = {
                            newChatMenuExpanded = false
                            onBeginCreateGroup()
                        })
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChanged,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                singleLine = true,
                placeholder = { Text("Search messages") },
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (filteredConversations.isEmpty()) {
            item {
                Text(
                    if (search.isBlank()) "Start a conversation from your contacts." else "No conversations match your search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        }
        items(filteredConversations, key = ConversationSummary::peerId) { conversation ->
            ConversationCard(conversation) { onOpenConversation(conversation.peerId) }
        }
        if (search.isBlank()) {
            item {
                Text("Nearby", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(when {
                            !nearbyActive -> "Connect without internet"
                            connectionCount > 0 -> "$connectionCount phone${if (connectionCount == 1) "" else "s"} connected"
                            else -> "Looking for people nearby"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(if (nearbyActive) "Keep BLAP open on both phones to connect."
                            else "Turn on nearby messaging to discover other phones running BLAP.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        if (!nearbyActive) {
                            Button(onClick = onStartNearby, modifier = Modifier.padding(top = 10.dp)) {
                                Text("Turn on nearby")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onStopNearby,
                                modifier = Modifier.padding(top = 10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Turn off nearby")
                            }
                        }
                        if (deniedPermissions.isNotEmpty()) {
                            Text("Allow nearby permissions to connect.", color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp))
                            TextButton(onClick = onOpenSettings) { Text("Open permissions") }
                        }
                    }
                }
            }
            if (nearbyActive) {
                items(devices, key = NearbyDevice::endpointId) { device ->
                    DeviceCard(device) { onConnect(device.endpointId) }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(conversation.name, conversation.connected)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conversation.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conversation.lastMessageAt > 0 && conversation.lastMessage.isNotBlank()) {
                        Text(formatConversationTime(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Text(conversation.lastMessage.ifBlank { "Say hello" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp))
                Text(when (conversation.type) {
                    ConversationType.OPEN_MESH -> "Public nearby chat"
                    ConversationType.PRIVATE_GROUP -> "${conversation.memberCount} members"
                    ConversationType.DIRECT -> when {
                        conversation.connected -> "Connected nearby"
                        conversation.onlineAccountLinked -> "Online account linked · not nearby"
                        else -> "Not connected nearby"
                    }
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp))
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
private fun DeviceCard(device: NearbyDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(device.name, true)
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("Tap to connect", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("CONNECT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Avatar(name: String, connected: Boolean) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (connected) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            color = if (connected) {
                MaterialTheme.colorScheme.onTertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun initialsOf(name: String) = name
    .split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")

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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
private fun ContactsScreen(
    contacts: List<SavedContact>,
    onlineReady: Boolean,
    search: String,
    onSearchChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onMessage: (String) -> Unit,
    onCheckOnline: (String) -> Unit,
) {
    val filtered = contacts.filter { contact ->
        search.isBlank() || listOf(
            contact.name,
            contact.phoneNumber,
            contact.email,
            contact.googleAccountEmail,
            contact.username,
            contact.instagramUrl,
            contact.linkedinUrl,
        ).any { it.contains(search, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Keep your people in one place. Tap a card to edit their details.",
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAdd) { Text("Add") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search contacts") },
            shape = RoundedCornerShape(15.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onImport) { Text("Import from phone") }
            TextButton(onClick = onScan) { Text("Scan contact QR") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (contacts.isEmpty()) "No contact cards saved yet" else "No contacts match your search",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                }
            } else {
                items(filtered, key = SavedContact::id) { contact ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(contact.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(contact.name, contact.linkedPeerId != null)
                            Column(
                                Modifier
                                    .padding(start = 12.dp)
                                    .weight(1f),
                            ) {
                                Text(contact.name, style = MaterialTheme.typography.titleMedium)
                                if (contact.username.isNotBlank()) {
                                    Text("@${contact.username}", color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    contact.email.ifBlank { contact.googleAccountEmail.ifBlank {
                                        contact.phoneNumber.ifBlank { "BLAP contact" } } },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    when {
                                        contact.cloudUserId.isNotBlank() && contact.username.isNotBlank() ->
                                            "Online as @${contact.username}"
                                        contact.cloudUserId.isNotBlank() -> "Online account found. Confirm a number match by QR."
                                        contact.linkedPeerId != null -> "Recognized on mesh"
                                        onlineReady && contact.username.isNotBlank() -> "Can find @${contact.username} online"
                                        onlineReady && (contact.email.isNotBlank() || contact.googleAccountEmail.isNotBlank()) ->
                                            "Can look up a verified account email online"
                                        onlineReady && contact.phoneNumber.isNotBlank() ->
                                            "Can look up this number online. Match is unverified"
                                        else -> "Saved locally. Sign in or pair by QR or Nearby to chat"
                                    },
                                    color = if (contact.linkedPeerId != null || contact.cloudUserId.isNotBlank()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = { onMessage(contact.id) }) { Text("Message") }
                                if (onlineReady && (contact.phoneNumber.isNotBlank() ||
                                        contact.email.isNotBlank() || contact.googleAccountEmail.isNotBlank() ||
                                        contact.username.isNotBlank())) {
                                    TextButton(onClick = { onCheckOnline(contact.id) }) { Text("Find online") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactEditorScreen(
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

@Composable
private fun ProfileEditorScreen(
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

@Composable
private fun ProfileForm(
    title: String,
    subtitle: String,
    profile: ContactProfile,
    onChanged: (ContactProfile) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    saveLabel: String,
    onDelete: (() -> Unit)? = null,
    isContact: Boolean = false,
    allowQrOnly: Boolean = false,
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
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                ProfileTextField("Name", profile.displayName) {
                    onChanged(profile.copy(displayName = it))
                }
            }
            if (isContact) item {
                ProfileTextField("BLAP username", profile.username) {
                    onChanged(profile.copy(username = it))
                }
            }
            item {
                PhoneNumberFields(profile.phoneNumber,
                    if (isContact) "Contact phone" else "Phone shown on card") { number ->
                    onChanged(profile.copy(
                        phoneNumber = number,
                    ))
                }
            }
            item {
                ProfileTextField("Email", profile.email, KeyboardType.Email) {
                    onChanged(profile.copy(email = it))
                }
            }
            item {
                ProfileTextField("Google account email", profile.googleAccountEmail, KeyboardType.Email) {
                    onChanged(profile.copy(googleAccountEmail = it))
                }
            }
            item {
                Text(
                    "A Google account is added by its email address. A saved address does not prove the account belongs to that person.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = profile.bio,
                    onValueChange = { onChanged(profile.copy(bio = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("About") },
                    placeholder = { Text("A short intro") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(15.dp),
                )
            }
            item {
                Text(
                    "Links & Social Media",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                ProfileTextField("Website", profile.websiteUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(websiteUrl = it))
                }
            }
            item {
                ProfileTextField("Instagram URL", profile.instagramUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(instagramUrl = it))
                }
            }
            item {
                ProfileTextField("X / Twitter URL", profile.xUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(xUrl = it))
                }
            }
            item {
                ProfileTextField("LinkedIn URL", profile.linkedinUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(linkedinUrl = it))
                }
            }
            item {
                ProfileTextField("GitHub URL", profile.githubUrl, KeyboardType.Uri) {
                    onChanged(profile.copy(githubUrl = it))
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = profile.displayName.isNotBlank() &&
                (!isContact || profile.phoneNumber.isNotBlank() || profile.email.isNotBlank() ||
                    profile.googleAccountEmail.isNotBlank() || profile.username.isNotBlank() || allowQrOnly),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(15.dp),
        ) { Text(saveLabel) }
        if (onDelete != null) {
            TextButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Delete contact", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirmingDelete && onDelete != null) {
        DeleteConfirmationDialog(
            title = "Delete this contact?",
            message = "Their saved contact card will be removed from BLAP.",
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun DeleteConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PhoneNumberFields(number: String, label: String = "Phone number (optional)", onChanged: (String) -> Unit) {
    val initial = remember { PhoneNumberParts.from(number) }
    var countryCode by rememberSaveable { mutableStateOf(initial.countryCode) }
    var nationalNumber by rememberSaveable { mutableStateOf(initial.nationalNumber) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = countryCode,
            onValueChange = { value ->
                countryCode = value.filter(Char::isDigit).take(3)
                onChanged(PhoneNumberParts(countryCode, nationalNumber).combined())
            },
            modifier = Modifier.width(104.dp),
            label = { Text("Code") },
            prefix = { Text("+") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = nationalNumber,
            onValueChange = { value ->
                nationalNumber = value.filter(Char::isDigit).take(15)
                onChanged(PhoneNumberParts(countryCode, nationalNumber).combined())
            },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(15.dp),
    )
}

@Composable
private fun MyCardScreen(profile: ContactProfile, peerId: String, onEdit: () -> Unit) {
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
                    contentDescription = "QR code for ${profile.displayName}'s BLAP contact card",
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

internal fun createQrBitmap(payload: String, size: Int = 900): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    val dark = android.graphics.Color.BLACK
    val light = android.graphics.Color.WHITE
    for (y in 0 until size) {
        for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) dark else light
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun AccountAccess(
    authAccount: AuthAccount,
    onCreateEmailAccount: (String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
) {
    var accountEmail by rememberSaveable { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var selectedMethod by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(authAccount) { accountPassword = "" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sign in or link an account", style = MaterialTheme.typography.titleMedium)
            if (authAccount.email.isNotBlank()) {
                Text(authAccount.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Email", "Google").forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedMethod == index,
                        onClick = { selectedMethod = index },
                        label = { Text(label) },
                    )
                }
            }
            when (selectedMethod) {
                0 -> {
                    if (authAccount.hasPassword) {
                        Text("Email and password connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedTextField(
                            value = accountEmail,
                            onValueChange = { accountEmail = it.take(120) },
                            label = { Text("Email") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = accountPassword,
                            onValueChange = { accountPassword = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onCreateEmailAccount(accountEmail, accountPassword) }) {
                                Text(if (authAccount.hasGoogle) "Add email sign-in" else "Create account")
                            }
                            if (!authAccount.hasGoogle) {
                                TextButton(onClick = { onSignInWithEmail(accountEmail, accountPassword) }) {
                                    Text("Sign in")
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (authAccount.hasGoogle) {
                        Text("Google connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedButton(onClick = onSignInWithGoogle) { Text("Continue with Google") }
                    }
                }
            }
            Text(
                "Nearby chat works offline after sign-in. Phone numbers can be kept on contact cards, but are not used to sign in.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscoverySettingsScreen(
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile,
    lookupPhoneNumber: String,
    enabled: Boolean,
    savedLookupPhoneNumber: String,
    savedEnabled: Boolean,
    onlineLookupStatus: String,
    onPhoneChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
            }
            Text("Find me", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            item {
                Text("These account details help others find you. They are separate from the phone, email and links shown on your QR contact card.")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Account username", style = MaterialTheme.typography.titleMedium)
                        Text("@${accountProfile.username}")
                        Text("Username search is not available yet. Share your QR card to pair directly.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sign-in email", style = MaterialTheme.typography.titleMedium)
                        Text(authAccount.email.ifBlank { "No email on this account" })
                        Text(
                            if (authAccount.emailVerified) "People can find this account by its exact email address."
                            else "Verify this email before others can find your account by it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Text("Phone lookup", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(if (lookupPhoneNumber != savedLookupPhoneNumber || enabled != savedEnabled)
                    "Unsaved changes. Save below to update online lookup."
                    else onlineLookupStatus,
                    color = MaterialTheme.colorScheme.primary)
            }
            item { PhoneNumberFields(lookupPhoneNumber, "Lookup number", onPhoneChanged) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = onEnabledChanged,
                        enabled = lookupPhoneNumber.isNotBlank())
                    Text("Let people find my account by this number")
                }
            }
            item {
                Text("This number is not verified. The number on your shared card can help nearby mesh matching, but it does not enable online lookup. Anyone who knows your lookup number may find this account once you save with the box checked.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text("Save discovery settings")
        }
    }
}

@Composable
private fun SettingsScreen(
    authAccount: AuthAccount,
    accountProfile: PublicAccountProfile,
    onSignOut: () -> Unit,
    onCreateEmailAccount: (String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
    contactCount: Int,
    connectionCount: Int,
    onEditProfile: () -> Unit,
    onShowDiscoverySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    nearbyActive: Boolean,
    onStartNearby: () -> Unit,
    onStopNearby: () -> Unit,
    venueStatus: String,
    checkingVenue: Boolean,
    onCheckVenue: () -> Unit,
    notificationSettings: ChatNotificationSettings,
    notificationPermissionGranted: Boolean,
    onNotificationSettingsChanged: (ChatNotificationSettings) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(accountProfile.displayName, style = MaterialTheme.typography.titleLarge)
                    if (accountProfile.username.isNotBlank()) Text("@${accountProfile.username}")
                    else Text("Guest profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (authAccount.email.isNotBlank()) Text(authAccount.email)
                    if (!authAccount.isAnonymous) {
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
            }
        }
        item {
            AccountAccess(
                authAccount = authAccount,
                onCreateEmailAccount = onCreateEmailAccount,
                onSignInWithEmail = onSignInWithEmail,
                onSignInWithGoogle = onSignInWithGoogle,
            )
        }
        item {
            SettingsCard(
                title = "Nearby messaging",
                detail = if (nearbyActive) "On. Other phones can find and connect to you." else "Off. Your saved chats are still available.",
                action = if (nearbyActive) "Turn off" else "Turn on",
                onClick = if (nearbyActive) onStopNearby else onStartNearby,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.animateContentSize().padding(16.dp)) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose which chat messages alert you on this phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NotificationSettingRow("Allow chat alerts", notificationSettings.enabled) {
                        onNotificationSettingsChanged(notificationSettings.copy(enabled = it))
                    }
                    AnimatedVisibility(notificationSettings.enabled) {
                        Column {
                        NotificationSettingRow("Direct messages", notificationSettings.direct) {
                            onNotificationSettingsChanged(notificationSettings.copy(direct = it))
                        }
                        NotificationSettingRow("Private groups", notificationSettings.privateGroups) {
                            onNotificationSettingsChanged(notificationSettings.copy(privateGroups = it))
                        }
                        NotificationSettingRow("Open mesh chat", notificationSettings.openMesh) {
                            onNotificationSettingsChanged(notificationSettings.copy(openMesh = it))
                        }
                        NotificationSettingRow("Show message previews", notificationSettings.showPreview) {
                            onNotificationSettingsChanged(notificationSettings.copy(showPreview = it))
                        }
                        if (!notificationPermissionGranted) {
                            Text("Android notifications are off for this app.", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRequestNotificationPermission) { Text("Allow in Android") }
                        }
                        }
                    }
                    Text(
                        "Alerts appear while CommonGround is running. Messages received while it is closed appear when you open the app, without a background alert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nearby places", style = MaterialTheme.typography.titleMedium)
                    Text(venueStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    TextButton(onClick = onCheckVenue, enabled = !checkingVenue) {
                        Text(if (checkingVenue) "Checking..." else "Find a place")
                    }
                }
            }
        }
        item {
            SettingsCard(
                title = "My profile and QR",
                detail = "Choose what appears when someone scans your card",
                action = "Edit",
                onClick = onEditProfile,
            )
        }
        if (!authAccount.isAnonymous) {
            item {
                SettingsCard(
                    title = "Find me",
                    detail = "Username, sign-in email and optional phone lookup",
                    action = "Open",
                    onClick = onShowDiscoverySettings,
                )
            }
        }
        item {
            SettingsCard(
                title = "Android permissions",
                detail = "Nearby devices, contacts, and location access",
                action = "Open",
                onClick = onOpenAppSettings,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("On this phone", style = MaterialTheme.typography.titleMedium)
                    Text("$contactCount saved contact card${if (contactCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$connectionCount active mesh link${if (connectionCount == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "Open public events and nearby messaging work as a guest. Sign in for protected or private events and online chat sync.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NotificationSettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChatContactProfileScreen(
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

@Composable
private fun SettingsCard(
    title: String,
    detail: String,
    action: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
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
            }) { Avatar(conversation.name, conversation.connected) }
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
            TextButton(onClick = { showPollDialog = true }) { Text("Poll") }
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
internal fun MessageBubble(message: ChatMessage, showSender: Boolean) {
    MessageBubble(
        item = PresentedMessage(message, ChatContent.Text(message.text)),
        showSender = showSender,
        onReply = {},
        onEdit = {},
        onDelete = {},
        onVote = {},
        showActions = false,
    )
}

@Composable
internal fun MessageBubble(
    item: PresentedMessage,
    showSender: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onVote: (Int) -> Unit,
    showActions: Boolean = true,
) {
    val message = item.message
    val mine = message.author == MessageAuthor.ME
    var menuExpanded by remember { mutableStateOf(false) }
    var swipeOffset by remember(message.id) { mutableFloatStateOf(0f) }
    val visibleSwipeOffset by animateFloatAsState(swipeOffset, label = "Reply swipe")
    val replyThreshold = with(LocalDensity.current) { 68.dp.toPx() }
    val swipeLimit = with(LocalDensity.current) { 88.dp.toPx() }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine && showSender) {
            Text(
                message.senderName.ifBlank { "Mesh member" },
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Box {
            if (showActions && !item.deleted) {
                Text(
                    "↩",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .offset { IntOffset(visibleSwipeOffset.roundToInt(), 0) }
                .pointerInput(message.id, showActions, item.deleted) {
                    if (showActions && !item.deleted) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                swipeOffset = (swipeOffset + amount).coerceIn(0f, swipeLimit)
                                change.consume()
                            },
                            onDragEnd = {
                                if (swipeOffset >= replyThreshold) onReply()
                                swipeOffset = 0f
                            },
                            onDragCancel = { swipeOffset = 0f },
                        )
                    }
                }
                .clip(RoundedCornerShape(17.dp))
                .border(
                    1.dp,
                    if (mine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(17.dp),
                )
                .background(
                    if (mine) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .clickable(enabled = showActions && !item.deleted) { menuExpanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val textColor = if (mine) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
                if (item.deleted) Text("Message deleted", color = textColor)
                else when (val content = item.content) {
                    is ChatContent.Text -> Text(content.body, color = textColor)
                    is ChatContent.Reply -> {
                        Text("Reply to: ${content.excerpt}", color = textColor,
                            style = MaterialTheme.typography.labelMedium)
                        Text(content.body, color = textColor)
                    }
                    is ChatContent.Poll -> {
                        Text(content.question, color = textColor, style = MaterialTheme.typography.titleMedium)
                        content.options.forEachIndexed { index, option ->
                            TextButton(onClick = { onVote(index) }) {
                                Text("${if (item.myVote == index) "✓ " else ""}$option · ${item.votes.getOrElse(index) { 0 }}")
                            }
                        }
                    }
                    else -> Unit
                }
                if (item.edited && !item.deleted) Text("Edited", color = textColor,
                    style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = showActions && menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Reply") }, onClick = { menuExpanded = false; onReply() })
                if (mine) {
                    if (item.content is ChatContent.Text || item.content is ChatContent.Reply) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuExpanded = false; onEdit() })
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
        val timestamp = formatTimestamp(message.sentAt)
        if (mine) {
            val status = when (message.status) {
                MessageStatus.PENDING -> "Waiting"
                MessageStatus.SENT -> "Sent"
                MessageStatus.DELIVERED -> "Delivered"
            }
            Text(
                "$timestamp · $status",
                modifier = Modifier.padding(top = 3.dp, end = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                timestamp,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(sentAt: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(sentAt))

@Composable
internal fun MessageComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: (String) -> Unit,
    sendLabel: String = "Send",
) {
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
        }
    }

    Row(
        Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                onTextChanged(it.take(1_000))
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            shape = RoundedCornerShape(17.dp),
        )
        Spacer(Modifier.width(9.dp))
        Button(onClick = send, enabled = text.isNotBlank(), modifier = Modifier.height(52.dp)) {
            Text(sendLabel)
        }
    }
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
