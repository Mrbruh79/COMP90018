package com.example.blap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.blap.chat.ChatViewModel
import com.example.blap.chat.ContactsViewModel
import com.example.blap.chat.GroupsViewModel
import com.example.blap.chat.ProfileViewModel
import com.example.blap.chat.MessagingSessionOwner
import com.example.blap.chat.MessagingViewModelFactory
import com.example.blap.auth.AuthViewModel
import com.example.blap.event.EventViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.NotificationSettingsRepository
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.example.blap.ui.NearbyChatApp
import com.example.blap.ui.screens.onboarding.OnboardingPreferences
import com.example.blap.ui.screens.onboarding.OnboardingScreen
import com.example.blap.ui.theme.CommonGroundTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.example.blap.ui.screens.auth.AuthActions
import com.example.blap.ui.screens.groups.GroupsActions
import com.example.blap.ui.screens.profile.ProfileActions
import com.example.blap.ui.screens.contacts.ContactsActions

class MainActivity : ComponentActivity() {
    private val appContainer get() = (application as BlapApplication).appContainer
    private val sessionOwner: MessagingSessionOwner by viewModels {
        appContainer.messagingSessionFactory(appContainer.authentication.account.uid)
    }
    private val featureFactory get() = MessagingViewModelFactory(sessionOwner.session)
    private val viewModel: ChatViewModel by viewModels { featureFactory }
    private val contactsViewModel: ContactsViewModel by viewModels { featureFactory }
    private val groupsViewModel: GroupsViewModel by viewModels { featureFactory }
    private val profileViewModel: ProfileViewModel by viewModels { featureFactory }
    private val authViewModel: AuthViewModel by viewModels { featureFactory }
    private val eventViewModel: EventViewModel by viewModels { featureFactory }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())
    private var pendingEventGpsEntry = false
    private var pendingEventQrScan = false
    private var pendingEventAdminAccess = false
    private lateinit var notificationSettingsStore: NotificationSettingsRepository
    private var notificationSettings by mutableStateOf(ChatNotificationSettings())
    private var notificationPermissionGranted by mutableStateOf(false)
    private var microphonePermissionGranted by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionGranted = granted }

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = NearbyPermissions.missing(this)
        deniedPermissions = missing
        if (missing.isEmpty()) {
            viewModel.startChat()
            when {
                pendingEventGpsEntry -> requestEventLocationPermission()
                pendingEventQrScan -> scanEventCheckInQrNow()
                pendingEventAdminAccess -> {
                    pendingEventAdminAccess = false
                    eventViewModel.requestEventAdminAccess()
                }
            }
        } else {
            pendingEventGpsEntry = false
            pendingEventQrScan = false
            pendingEventAdminAccess = false
        }
    }

    private val venuePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (VenuePermissions.missing(this).isEmpty()) checkForNearbyVenue()
        else viewModel.updateVenueStatus("Allow location access to find nearby places.")
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) importDeviceContacts()
        else viewModel.showError("Contacts permission is needed to import device contacts.")
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphonePermissionGranted = granted
        if (!granted) viewModel.showError("Microphone permission is needed to send voice messages.")
    }

    private val eventLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (VenuePermissions.missing(this).isEmpty()) checkEventLocation()
        else {
            pendingEventGpsEntry = false
            viewModel.showError(
                "Allow location access, or use admin approval for a private event / the venue QR for a public event.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        notificationSettingsStore = appContainer.notificationSettingsFor(sessionOwner.session.dependencies.accountId)
        notificationSettings = notificationSettingsStore.load()
        notificationPermissionGranted = hasNotificationPermission()
        microphonePermissionGranted = hasMicrophonePermission()
        authViewModel.initialize()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.accountChanges.collect { change ->
                    change.setupError?.let { intent.putExtra(ACCOUNT_SETUP_ERROR, it) }
                    if (change.clearCredentials) runCatching {
                        CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest())
                    }
                    restartForAccountChange()
                }
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        val onboardingPreferences = OnboardingPreferences(this)

        setContent {
            CommonGroundTheme {
                var showOnboarding by rememberSaveable {
                    mutableStateOf(!onboardingPreferences.hasSeenOnboarding())
                }
                if (showOnboarding) {
                    OnboardingScreen(
                        onGetStarted = {
                            onboardingPreferences.markSeen()
                            showOnboarding = false
                        },
                    )
                    return@CommonGroundTheme
                }

                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val eventUiState by eventViewModel.uiState.collectAsStateWithLifecycle()
                val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                val authAccount = authUiState.account
                val accountProfile = authUiState.profile
                val accountProfileLoading = authUiState.profileLoading
                NearbyChatApp(
                    uiState = uiState,
                    eventUiState = eventUiState,
                    deniedPermissions = deniedPermissions.map(NearbyPermissions::displayName),
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
                        onScanContact = ::scanContactCard,
                        onImportContacts = ::requestContactImport,
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
                    authActions = AuthActions(
                        onCreateEmailAccount = authViewModel::createEmailAccount,
                        onSignInWithEmail = authViewModel::signInWithEmail,
                        onSignInWithGoogle = ::signInWithGoogle,
                        onRegisterEmail = authViewModel::registerEmail,
                        onCompleteAccountProfile = authViewModel::completeAccountProfile,
                        onRetryAccountProfile = authViewModel::loadAccountProfile,
                        onSignOut = ::signOut,
                    ),
                    onStartChat = ::requestNearbyPermissionsAndStart,
                    onStopChat = viewModel::stopChat,
                    onCheckVenue = ::requestVenueCheck,
                    onConnect = viewModel::connectToDevice,
                    onOpenConversation = viewModel::openConversation,
                    onBackToChats = viewModel::showConversationList,
                    onSendMessage = viewModel::sendMessage,
                    onSendReply = viewModel::sendReply,
                    onCreatePoll = viewModel::createPoll,
                    onVoteInPoll = viewModel::voteInPoll,
                    onEditMessage = viewModel::editMessage,
                    onDeleteMessage = viewModel::deleteMessage,
                    onSendVoice = viewModel::sendVoice,
                    microphonePermissionGranted = microphonePermissionGranted,
                    onRequestMicrophonePermission = ::requestMicrophonePermission,
                    onMessageDraftChanged = viewModel::updateMessageDraft,
                    onDisconnect = viewModel::disconnect,
                    notificationSettings = notificationSettings,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onNotificationSettingsChanged = ::updateNotificationSettings,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onShowEvents = eventViewModel::showEvents,
                    onBeginCreateEvent = eventViewModel::beginCreateEvent,
                    onBeginEditEvent = eventViewModel::beginEditEvent,
                    onCreateEvent = { request ->
                        eventViewModel.createEvent(
                            request.title,
                            request.description,
                            request.venueName,
                            request.latitude,
                            request.longitude,
                            request.radiusMetres,
                            request.startsAt,
                            request.endsAt,
                            request.visibility,
                            request.requiresSignIn,
                        )
                    },
                    onOpenEvent = eventViewModel::openEvent,
                    onUpdateEvent = eventViewModel::updateSelectedEvent,
                    onDeleteEvent = eventViewModel::deleteSelectedEvent,
                    onJoinEvent = eventViewModel::joinSelectedEvent,
                    onInviteToEvent = eventViewModel::inviteToSelectedEvent,
                    onSearchEventParticipant = eventViewModel::searchSelectedEventParticipant,
                    onAcceptEventInvitation = eventViewModel::acceptEventInvitation,
                    onDeclineEventInvitation = eventViewModel::declineEventInvitation,
                    onRevokeEventInvitation = eventViewModel::revokeEventInvitation,
                    onLeaveEvent = eventViewModel::leaveSelectedEvent,
                    onPromoteEventMember = eventViewModel::promoteEventMember,
                    onRemoveEventMember = eventViewModel::removeEventMember,
                    onDeleteEventData = eventViewModel::deleteSelectedEventData,
                    onShowEventAnnouncements = eventViewModel::showEventAnnouncements,
                    onPublishEventAnnouncement = eventViewModel::publishEventAnnouncement,
                    onShowEventDiscussion = eventViewModel::showEventDiscussion,
                    onLoadMoreEventDiscussion = eventViewModel::loadMoreEventDiscussion,
                    onOpenEventDiscussionThread = eventViewModel::openEventDiscussionThread,
                    onCreateEventDiscussionComment = eventViewModel::createEventDiscussionComment,
                    onToggleEventDiscussionLike = eventViewModel::toggleEventDiscussionLike,
                    onDeleteEventDiscussionComment = eventViewModel::deleteEventDiscussionComment,
                    onRequestEventGpsEntry = ::requestEventGpsEntry,
                    onRequestEventAdminAccess = ::requestEventAdminAccess,
                    onApproveEventAdminAccess = eventViewModel::approveEventAdminAccess,
                    onScanEventQr = ::requestEventQrScan,
                    onShowEventQr = eventViewModel::showEventCheckInQr,
                    onHideEventQr = eventViewModel::hideEventCheckInQr,
                    onShowSavedEventChat = eventViewModel::showSavedEventChat,
                    onSendEventMessage = eventViewModel::sendEventMessage,
                    onEventBack = eventViewModel::eventBack,
                    getCurrentLocation = appContainer.locationProvider::getFreshLocation,
                    searchPlaces = appContainer.placeSearch::search,
                    onConversationSearchChanged = viewModel::updateConversationSearch,
                    onSystemBack = sessionOwner.session.navigation::handleBack,
                    onDismissError = viewModel::dismissError,
                    onDismissEventMessage = eventViewModel::dismissEventMessage,
                    onOpenSettings = ::openAppSettings,
                )
            }
        }
        intent.getStringExtra(ACCOUNT_SETUP_ERROR)?.let { message ->
            intent.removeExtra(ACCOUNT_SETUP_ERROR)
            viewModel.showError(message)
        }
    }

    private fun signOut() = authViewModel.signOut()

    private fun restartForAccountChange() {
        viewModelStore.clear()
        recreate()
    }

    companion object {
        private const val ACCOUNT_SETUP_ERROR = "account_setup_error"
    }

    private fun signInWithGoogle() {
        val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (resourceId == 0) {
            viewModel.showError("Google sign-in needs an updated Firebase config with a Web OAuth client.")
            return
        }
        val clientId = getString(resourceId)
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(clientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    viewModel.showError("Google did not return a sign-in token.")
                    return@launch
                }
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                authViewModel.signInWithGoogleToken(token)
            } catch (_: GetCredentialCancellationException) {
                viewModel.showNotice("Google sign-in cancelled.")
            } catch (_: NoCredentialException) {
                viewModel.showError("No Google account is available. Add one in device Settings, then try again.")
            } catch (error: Exception) {
                viewModel.showError(error.localizedMessage ?: "Google sign-in was cancelled or failed.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationPermissionGranted = hasNotificationPermission()
        microphonePermissionGranted = hasMicrophonePermission()
        if (deniedPermissions.isNotEmpty()) deniedPermissions = NearbyPermissions.missing(this)
        authViewModel.refreshAccount()
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicrophonePermission() {
        if (hasMicrophonePermission()) {
            microphonePermissionGranted = true
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateNotificationSettings(value: ChatNotificationSettings) {
        val old = notificationSettings
        notificationSettings = value
        notificationSettingsStore.save(value)
        if (value.enabled && (!old.enabled || (!old.direct && value.direct) ||
                (!old.privateGroups && value.privateGroups) || (!old.openMesh && value.openMesh))) {
            requestNotificationPermission()
        }
    }

    private fun requestNearbyPermissionsAndStart() {
        val missing = NearbyPermissions.missing(this)
        if (missing.isEmpty()) {
            deniedPermissions = emptyList()
            viewModel.startChat()
        } else {
            nearbyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestEventGpsEntry() {
        pendingEventGpsEntry = true
        pendingEventQrScan = false
        pendingEventAdminAccess = false
        val missingNearby = NearbyPermissions.missing(this)
        if (missingNearby.isEmpty()) {
            viewModel.startChat()
            requestEventLocationPermission()
        } else {
            nearbyPermissionLauncher.launch(missingNearby.toTypedArray())
        }
    }

    private fun requestEventAdminAccess() {
        pendingEventGpsEntry = false
        pendingEventQrScan = false
        pendingEventAdminAccess = true
        val missingNearby = NearbyPermissions.missing(this)
        if (missingNearby.isEmpty()) {
            viewModel.startChat()
            pendingEventAdminAccess = false
            eventViewModel.requestEventAdminAccess()
        } else {
            nearbyPermissionLauncher.launch(missingNearby.toTypedArray())
        }
    }

    private fun requestEventLocationPermission() {
        val missing = VenuePermissions.missing(this)
        if (missing.isEmpty()) checkEventLocation()
        else eventLocationPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun checkEventLocation() {
        lifecycleScope.launch {
            try {
                val location = appContainer.locationProvider.getFreshLocation()
                if (location == null) {
                    viewModel.showError(
                        "A current location was not available. Use admin approval for a private event or the venue QR for a public event.",
                    )
                } else {
                    eventViewModel.enterEventWithGps(
                        location.latitude,
                        location.longitude,
                        location.accuracyMetres,
                    )
                }
            } catch (_: Exception) {
                viewModel.showError(
                    "Location could not be checked. Use admin approval for a private event or the venue QR for a public event.",
                )
            } finally {
                pendingEventGpsEntry = false
            }
        }
    }

    private fun requestEventQrScan() {
        pendingEventQrScan = true
        pendingEventGpsEntry = false
        pendingEventAdminAccess = false
        val missingNearby = NearbyPermissions.missing(this)
        if (missingNearby.isEmpty()) {
            viewModel.startChat()
            scanEventCheckInQrNow()
        } else {
            nearbyPermissionLauncher.launch(missingNearby.toTypedArray())
        }
    }

    private fun scanEventCheckInQrNow() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                pendingEventQrScan = false
                val payload = barcode.rawValue
                if (payload.isNullOrBlank()) viewModel.showError("This QR code has no check-in data.")
                else eventViewModel.enterEventWithQr(payload)
            }
            .addOnFailureListener { error ->
                pendingEventQrScan = false
                viewModel.showError(error.message ?: "The venue check-in QR could not be scanned.")
            }
    }

    private fun openAppSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
    }

    private fun requestContactImport() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            importDeviceContacts()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun importDeviceContacts() {
        lifecycleScope.launch(Dispatchers.IO) {
            contactsViewModel.importDeviceContacts(DeviceContactsReader.read(applicationContext))
        }
    }

    private fun scanContactCard() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val payload = barcode.rawValue
                if (payload.isNullOrBlank()) viewModel.showError("This QR code has no readable contact data.")
                else contactsViewModel.importScannedContactCard(payload)
            }
            .addOnFailureListener { error ->
                viewModel.showError(error.message ?: "The contact QR could not be scanned.")
            }
    }

    private fun checkForNearbyVenue() {
        viewModel.updateVenueStatus("Looking for a nearby place...", checking = true)
        lifecycleScope.launch {
            val result = try {
                val venue = appContainer.venues.findNearbyVenue()
                if (venue == null) "No places found nearby." else "Nearby: ${venue.name}"
            } catch (exception: Exception) {
                "Could not find nearby places. Check your internet and location settings."
            }
            viewModel.updateVenueStatus(result)
        }
    }

    private fun requestVenueCheck() {
        viewModel.updateVenueStatus("Connecting to nearby places...", checking = true)
        authViewModel.ensureSignedIn { success ->
            if (success) requestVenuePermissionAndCheck()
            else viewModel.updateVenueStatus("Could not connect. Check your internet and try again.")
        }
    }

    private fun requestVenuePermissionAndCheck() {
        val missing = VenuePermissions.missing(this)
        if (missing.isEmpty()) checkForNearbyVenue()
        else venuePermissionLauncher.launch(missing.toTypedArray())
    }
}
