package com.example.blap.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.example.blap.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.blap.chat.ChatScreen
import com.example.blap.chat.ChatMessage
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
import com.example.blap.chat.SavedContact
import com.example.blap.ui.theme.OnSentBubble
import com.example.blap.ui.theme.SentBubble
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NearbyChatApp(
    uiState: ChatUiState,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onStartChat: () -> Unit,
    onCompleteSetup: () -> Unit,
    onStopChat: () -> Unit,
    onCheckVenue: () -> Unit,
    onConnect: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onBackToChats: () -> Unit,
    onSendMessage: (String) -> Unit,
    onMessageDraftChanged: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onBeginCreateGroup: () -> Unit,
    onGroupNameChanged: (String) -> Unit,
    onToggleGroupMember: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onManageContacts: () -> Unit,
    onBeginAddContact: () -> Unit,
    onOpenContact: (String) -> Unit,
    onMessageContact: (String) -> Unit,
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
    onConversationSearchChanged: (String) -> Unit,
    onContactSearchChanged: (String) -> Unit,
    onBeginGroupSettings: () -> Unit,
    onSaveGroupSettings: () -> Unit,
    onSystemBack: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(
        enabled = uiState.screen != ChatScreen.WELCOME && uiState.screen != ChatScreen.CHATS,
        onBack = onSystemBack,
    )
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error, uiState.notice) {
        val message = uiState.error ?: uiState.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissError()
    }

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
                    .imePadding()
                    .padding(horizontal = 18.dp),
            ) {
                if (uiState.screen in TOP_LEVEL_SCREENS ||
                    uiState.screen == ChatScreen.WELCOME
                    ) Header(uiState)
                when (uiState.screen) {
                    ChatScreen.WELCOME -> WelcomeScreen(
                        name = uiState.displayName,
                        phoneNumber = uiState.phoneNumber,
                        deniedPermissions = deniedPermissions,
                        onNameChanged = onNameChanged,
                        onPhoneChanged = onPhoneChanged,
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
                        onOpenSettings = onOpenSettings,
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
                        search = uiState.contactSearch,
                        onSearchChanged = onContactSearchChanged,
                        onAdd = onBeginAddContact,
                        onOpen = onOpenContact,
                        onScan = onScanContact,
                        onImport = onImportContacts,
                        onMessage = onMessageContact,
                    )

                    ChatScreen.EDITING_CONTACT -> ContactEditorScreen(
                        profile = uiState.contactDraftProfile(),
                        source = uiState.contactSourceDraft,
                        isExisting = uiState.selectedContactId != null,
                        onChanged = onContactDraftChanged,
                        onSave = onSaveContact,
                        onDelete = onDeleteContact,
                        onBack = onManageContacts,
                    )

                    ChatScreen.SHOWING_MY_CARD -> MyCardScreen(
                        profile = uiState.profile(),
                        onEdit = onEditProfile,
                    )

                    ChatScreen.EDITING_PROFILE -> ProfileEditorScreen(
                        profile = uiState.profileDraft ?: uiState.profile(),
                        onChanged = onProfileChanged,
                        onSave = onSaveProfile,
                        onBack = onCancelProfile,
                    )

                    ChatScreen.SETTINGS -> SettingsScreen(
                        contactCount = uiState.savedContacts.size,
                        connectionCount = uiState.directConnectionCount,
                        onEditProfile = onEditProfile,
                        onOpenAppSettings = onOpenSettings,
                        nearbyActive = uiState.nearbyActive,
                        onStartNearby = onStartChat,
                        onStopNearby = onStopChat,
                        venueStatus = uiState.venueStatus,
                        checkingVenue = uiState.checkingVenue,
                        onCheckVenue = onCheckVenue,
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
}

private val TOP_LEVEL_SCREENS = setOf(
    ChatScreen.CHATS,
    ChatScreen.MANAGING_CONTACTS,
    ChatScreen.SHOWING_MY_CARD,
    ChatScreen.SETTINGS,
)

private fun ChatUiState.profile() = ContactProfile(
    displayName = displayName,
    phoneNumber = phoneNumber,
    email = profileEmail,
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
            selected = state == ChatScreen.SHOWING_MY_CARD,
            onClick = onMyCard,
            icon = { Icon(painterResource(R.drawable.ic_qr), contentDescription = null) },
            label = { Text("My card") },
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
    val connectedCount = state.directConnectionCount
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("BLAP", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        val text = when {
            !state.nearbyActive -> "Nearby off"
            connectedCount == 0 -> "Finding nearby"
            else -> "$connectedCount connected"
        }
        Text(
            text,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 11.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun WelcomeScreen(
    name: String,
    phoneNumber: String,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onPhoneChanged: (String) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Text("A little closer.\nEven offline.", style = MaterialTheme.typography.displaySmall)
            Text(
                "Set up your profile to get started. You can turn on nearby messaging when you are ready.",
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Your name", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        placeholder = { Text("Name shown to nearby people") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onStart() }),
                        shape = RoundedCornerShape(15.dp),
                    )
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = onPhoneChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        singleLine = true,
                        label = { Text("Your phone number") },
                        supportingText = { Text("Include country code so contacts can recognize you") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onStart() }),
                        shape = RoundedCornerShape(15.dp),
                    )
                    Button(
                        onClick = onStart,
                        enabled = name.isNotBlank() && phoneNumber.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text("Continue")
                    }
                }
            }

            if (deniedPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier.padding(top = 15.dp),
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
    onOpenSettings: () -> Unit,
) {
    val filteredConversations = conversations.filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true) ||
            it.lastMessage.contains(search, ignoreCase = true)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        item {
            Text("Messages", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onManageContacts) { Text("New message") }
                TextButton(onClick = onBeginCreateGroup) { Text("New group") }
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
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
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
                    ConversationType.DIRECT -> if (conversation.connected) "Connected nearby" else "Offline"
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
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = if (connected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = FontWeight.Bold,
        )
    }
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
                                        else -> "Will match by phone number when they join"
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
    search: String,
    onSearchChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val filtered = contacts.filter { contact ->
        search.isBlank() || listOf(
            contact.name,
            contact.phoneNumber,
            contact.email,
            contact.instagramUrl,
            contact.linkedinUrl,
        ).any { it.contains(search, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Contacts", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onAdd) { Text("Add") }
        }
        Text(
            "Keep your people in one place. Tap a card to edit their details.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
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
                                Text(contact.email.ifBlank { contact.phoneNumber }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (contact.linkedPeerId != null) "Recognized on mesh" else "Waiting to match",
                                    color = if (contact.linkedPeerId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onMessage(contact.id) }) { Text("Message") }
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
            "Check these details before saving the card to BLAP."
        } else {
            "Add contact details and any social profiles you want to keep together."
        },
        profile = profile,
        onChanged = onChanged,
        onSave = onSave,
        onBack = onBack,
        saveLabel = if (isExisting) "Save changes" else "Save contact",
        onDelete = if (isExisting) onDelete else null,
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
        title = "Edit my card",
        subtitle = "Only details you put here are included when someone scans your QR code.",
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
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
            item {
                ProfileTextField("Phone number", profile.phoneNumber, KeyboardType.Phone) {
                    onChanged(profile.copy(phoneNumber = it))
                }
            }
            item {
                ProfileTextField("Email", profile.email, KeyboardType.Email) {
                    onChanged(profile.copy(email = it))
                }
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
            item { Text("Links", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
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
            enabled = profile.displayName.isNotBlank() && profile.phoneNumber.isNotBlank(),
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
private fun MyCardScreen(profile: ContactProfile, onEdit: () -> Unit) {
    val payload = remember(profile) { ContactCardCodec.encode(profile) }
    val bitmap = remember(payload) { createQrBitmap(payload) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("My card", style = MaterialTheme.typography.headlineMedium)
                    Text("Share your details with a scan", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onEdit) { Text("Edit") }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(top = 18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(24.dp),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR code for ${profile.displayName}'s BLAP contact card",
                    modifier = Modifier
                        .size(280.dp)
                        .padding(16.dp),
                )
            }
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(profile.displayName.ifBlank { "Your name" }, style = MaterialTheme.typography.headlineMedium)
                    Text(profile.phoneNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (profile.email.isNotBlank()) Text(profile.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.bio.isNotBlank()) Text(profile.bio, modifier = Modifier.padding(top = 12.dp))
                    val links = listOf(
                        "Website" to profile.websiteUrl,
                        "Instagram" to profile.instagramUrl,
                        "X / Twitter" to profile.xUrl,
                        "LinkedIn" to profile.linkedinUrl,
                        "GitHub" to profile.githubUrl,
                    ).filter { it.second.isNotBlank() }
                    links.forEach { (label, value) ->
                        Text("$label · $value", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }
    }
}

private fun createQrBitmap(payload: String, size: Int = 900): Bitmap {
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
private fun SettingsScreen(
    contactCount: Int,
    connectionCount: Int,
    onEditProfile: () -> Unit,
    onOpenAppSettings: () -> Unit,
    nearbyActive: Boolean,
    onStartNearby: () -> Unit,
    onStopNearby: () -> Unit,
    venueStatus: String,
    checkingVenue: Boolean,
    onCheckVenue: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text("Identity, privacy, and device access", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                detail = "Choose what you share on your contact card",
                action = "Edit",
                onClick = onEditProfile,
            )
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
                "BLAP exchanges chat data over nearby mesh links. Contact cards are shared only when you display or scan their QR code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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
    draft: String,
    onDraftChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenGroupSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
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
            Avatar(conversation.name, conversation.connected)
            Column(
                Modifier
                    .padding(start = 10.dp)
                    .weight(1f),
            ) {
                Text(conversation.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        conversation.type == ConversationType.OPEN_MESH && directConnectionCount > 0 ->
                            "$directConnectionCount direct link${if (directConnectionCount == 1) "" else "s"} · relaying through mesh"
                        conversation.type == ConversationType.OPEN_MESH -> "Public room · no direct links"
                        conversation.type == ConversationType.PRIVATE_GROUP && directConnectionCount > 0 ->
                            "${conversation.memberCount} members · relaying through mesh"
                        conversation.type == ConversationType.PRIVATE_GROUP ->
                            "${conversation.memberCount} members · messages will wait"
                        conversation.connected -> "Connected nearby"
                        else -> "Offline, messages will wait"
                    },
                    color = if (conversation.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (conversation.connected && conversation.type == ConversationType.DIRECT) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            } else if (conversation.type == ConversationType.PRIVATE_GROUP) {
                TextButton(onClick = onOpenGroupSettings) { Text("Group info") }
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
            if (messages.isEmpty()) {
                item { Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(messages.asReversed(), key = ChatMessage::id) { message ->
                    MessageBubble(message, showSender = conversation.type != ConversationType.DIRECT)
                }
            }
        }
        MessageComposer(text = draft, onTextChanged = onDraftChanged, onSend = onSend)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showSender: Boolean) {
    val mine = message.author == MessageAuthor.ME
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
        Text(
            message.text,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(if (mine) SentBubble else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (mine) OnSentBubble else MaterialTheme.colorScheme.onSurface,
        )
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
private fun MessageComposer(text: String, onTextChanged: (String) -> Unit, onSend: (String) -> Unit) {
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
            Text("Send")
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
