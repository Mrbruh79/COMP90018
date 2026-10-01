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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.blap.auth.AuthManager
import com.example.blap.auth.AuthAccount
import com.example.blap.auth.AccountProfileManager
import com.example.blap.auth.PublicAccountProfile
import com.example.blap.chat.ChatViewModel
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.NotificationSettingsRepository
import com.example.blap.chat.ContactProfile
import com.example.blap.chat.IdentityStore
import com.example.blap.chat.PrivateProfileSnapshot
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

class MainActivity : ComponentActivity() {
    private val appContainer get() = (application as BlapApplication).appContainer
    private val viewModel: ChatViewModel by viewModels {
        appContainer.chatViewModelFactory(AuthManager.onlineUserId)
    }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())
    private var pendingEventGpsEntry = false
    private var pendingEventQrScan = false
    private var pendingEventAdminAccess = false
    private var authAccount by mutableStateOf(AuthAccount())
    private var accountProfile by mutableStateOf<PublicAccountProfile?>(null)
    private var accountProfileLoading by mutableStateOf(false)
    private lateinit var notificationSettingsStore: NotificationSettingsRepository
    private var notificationSettings by mutableStateOf(ChatNotificationSettings())
    private var notificationPermissionGranted by mutableStateOf(false)
    private var microphonePermissionGranted by mutableStateOf(false)
    private val privateProfileStore get() = appContainer.privateProfiles

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
                    viewModel.requestEventAdminAccess()
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
        authAccount = AuthManager.account
        notificationSettingsStore = appContainer.notificationSettingsFor(AuthManager.onlineUserId)
        notificationSettings = notificationSettingsStore.load()
        notificationPermissionGranted = hasNotificationPermission()
        microphonePermissionGranted = hasMicrophonePermission()
        accountProfileLoading = authAccount.uid.isNotBlank()
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
                val eventUiState by viewModel.eventUiState.collectAsStateWithLifecycle()
                NearbyChatApp(
                    uiState = uiState,
                    eventUiState = eventUiState,
                    deniedPermissions = deniedPermissions.map(NearbyPermissions::displayName),
                    onNameChanged = viewModel::updateDisplayName,
                    authAccount = authAccount,
                    accountProfile = accountProfile,
                    accountProfileLoading = accountProfileLoading,
                    onRegisterEmail = ::registerEmail,
                    onCompleteAccountProfile = ::completeAccountProfile,
                    onRetryAccountProfile = ::loadAccountProfile,
                    onSignOut = ::signOut,
                    onCreateEmailAccount = ::createEmailAccount,
                    onSignInWithEmail = ::signInWithEmail,
                    onSignInWithGoogle = ::signInWithGoogle,
                    onStartChat = ::requestNearbyPermissionsAndStart,
                    onCompleteSetup = viewModel::completeSetup,
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
                    onOpenChatContactProfile = viewModel::openCurrentChatProfile,
                    onCloseChatContactProfile = viewModel::closeCurrentChatProfile,
                    onSaveCurrentChatContact = viewModel::saveCurrentChatContact,
                    onMessageDraftChanged = viewModel::updateMessageDraft,
                    onDisconnect = viewModel::disconnect,
                    onOpenCreateGroup = viewModel::beginCreateGroup,
                    onGroupNameChanged = viewModel::updateGroupName,
                    onToggleGroupMember = viewModel::toggleGroupMember,
                    onCreateGroup = viewModel::createPrivateGroup,
                    onManageContacts = viewModel::beginManageContacts,
                    onBeginAddContact = viewModel::beginAddContact,
                    onOpenContact = viewModel::openContact,
                    onCloseContactEditor = viewModel::closeContactEditor,
                    onMessageContact = viewModel::messageContact,
                    onCheckContactOnline = viewModel::checkContactOnline,
                    onSelectOnlineAccount = viewModel::selectOnlineAccount,
                    onCancelAccountSelection = viewModel::cancelAccountSelection,
                    onContactDraftChanged = viewModel::updateContactDraft,
                    onDeleteContact = viewModel::deleteContact,
                    onScanContact = ::scanContactCard,
                    onSaveContact = viewModel::saveContact,
                    onImportContacts = ::requestContactImport,
                    onShowMyCard = viewModel::showMyCard,
                    onEditProfile = viewModel::editProfile,
                    onProfileChanged = viewModel::updateProfile,
                    onSaveProfile = viewModel::saveProfile,
                    onCancelProfile = viewModel::cancelProfileEdit,
                    onShowSettingsScreen = viewModel::showSettings,
                    notificationSettings = notificationSettings,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onNotificationSettingsChanged = ::updateNotificationSettings,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onShowDiscoverySettings = viewModel::showDiscoverySettings,
                    onDiscoveryPhoneChanged = viewModel::updateDiscoveryPhone,
                    onDiscoveryEnabledChanged = viewModel::updateDiscoveryEnabled,
                    onSaveDiscoverySettings = viewModel::saveDiscoverySettings,
                    onCancelDiscoverySettings = viewModel::cancelDiscoveryEdit,
                    onShowEvents = viewModel::showEvents,
                    onBeginCreateEvent = viewModel::beginCreateEvent,
                    onBeginEditEvent = viewModel::beginEditEvent,
                    onCreateEvent = { request ->
                        viewModel.createEvent(
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
                    onOpenEvent = viewModel::openEvent,
                    onUpdateEvent = viewModel::updateSelectedEvent,
                    onDeleteEvent = viewModel::deleteSelectedEvent,
                    onJoinEvent = viewModel::joinSelectedEvent,
                    onInviteToEvent = viewModel::inviteToSelectedEvent,
                    onSearchEventParticipant = viewModel::searchSelectedEventParticipant,
                    onAcceptEventInvitation = viewModel::acceptEventInvitation,
                    onDeclineEventInvitation = viewModel::declineEventInvitation,
                    onRevokeEventInvitation = viewModel::revokeEventInvitation,
                    onLeaveEvent = viewModel::leaveSelectedEvent,
                    onPromoteEventMember = viewModel::promoteEventMember,
                    onRemoveEventMember = viewModel::removeEventMember,
                    onDeleteEventData = viewModel::deleteSelectedEventData,
                    onShowEventAnnouncements = viewModel::showEventAnnouncements,
                    onPublishEventAnnouncement = viewModel::publishEventAnnouncement,
                    onShowEventDiscussion = viewModel::showEventDiscussion,
                    onLoadMoreEventDiscussion = viewModel::loadMoreEventDiscussion,
                    onOpenEventDiscussionThread = viewModel::openEventDiscussionThread,
                    onCreateEventDiscussionComment = viewModel::createEventDiscussionComment,
                    onToggleEventDiscussionLike = viewModel::toggleEventDiscussionLike,
                    onDeleteEventDiscussionComment = viewModel::deleteEventDiscussionComment,
                    onRequestEventGpsEntry = ::requestEventGpsEntry,
                    onRequestEventAdminAccess = ::requestEventAdminAccess,
                    onApproveEventAdminAccess = viewModel::approveEventAdminAccess,
                    onScanEventQr = ::requestEventQrScan,
                    onShowEventQr = viewModel::showEventCheckInQr,
                    onHideEventQr = viewModel::hideEventCheckInQr,
                    onShowSavedEventChat = viewModel::showSavedEventChat,
                    onSendEventMessage = viewModel::sendEventMessage,
                    onEventBack = viewModel::eventBack,
                    onConversationSearchChanged = viewModel::updateConversationSearch,
                    onContactSearchChanged = viewModel::updateContactSearch,
                    onBeginGroupSettings = viewModel::beginGroupSettings,
                    onSaveGroupSettings = viewModel::saveGroupSettings,
                    onSystemBack = viewModel::handleBack,
                    onDismissError = viewModel::dismissError,
                    onDismissEventMessage = viewModel::dismissEventMessage,
                    onOpenSettings = ::openAppSettings,
                )
            }
        }
        intent.getStringExtra(ACCOUNT_SETUP_ERROR)?.let { message ->
            intent.removeExtra(ACCOUNT_SETUP_ERROR)
            viewModel.showError(message)
        }
        if (authAccount.uid.isNotBlank()) {
            loadAccountProfile()
        } else if (!authAccount.isAnonymous) {
            AuthManager.ensureGuestSession {
                authAccount = AuthManager.account
                viewModel.accountChanged(authAccount.uid)
            }
        }
    }

    private fun localIdentity(): IdentityStore = appContainer.identityFor(AuthManager.onlineUserId)

    private fun loadAccountProfile() {
        if (authAccount.uid.isBlank()) return
        val loadingUid = authAccount.uid
        accountProfileLoading = true
        lifecycleScope.launch {
            val local = localIdentity().getProfile()
            val remote = runCatching { AccountProfileManager.load() }
            val profile = remote.getOrNull() ?: if (local.username.isNotBlank() && local.displayName.isNotBlank()) {
                runCatching { AccountProfileManager.claim(local.username, local.displayName) }.getOrNull()
                    ?: if (remote.isFailure) PublicAccountProfile(local.username, local.displayName) else null
            } else null
            if (authAccount.uid != loadingUid) return@launch
            if (profile != null) {
                val privateCopy = runCatching { privateProfileStore.load(loadingUid) }
                if (privateCopy.isSuccess) {
                    viewModel.restorePrivateProfile(
                        privateCopy.getOrNull(), profile.username, profile.displayName,
                    )
                }
                viewModel.applyAccountProfile(profile.username, profile.displayName)
            } else if (remote.isFailure) {
                viewModel.showError("Could not load your account. Check your connection and retry.")
            }
            accountProfile = profile
            accountProfileLoading = false
        }
    }

    private fun completeAccountProfile(username: String, displayName: String) {
        lifecycleScope.launch {
            try {
                val profile = AccountProfileManager.claim(username, displayName)
                val identity = localIdentity()
                identity.saveProfile(identity.getProfile().copy(
                    username = profile.username, displayName = profile.displayName,
                ))
                accountProfile = profile
                viewModel.restorePrivateProfile(null, profile.username, profile.displayName)
                viewModel.applyAccountProfile(profile.username, profile.displayName)
            } catch (error: Exception) {
                viewModel.showError(error.localizedMessage ?: "Could not save your username.")
            }
        }
    }

    private fun registerEmail(email: String, password: String, username: String, displayName: String) {
        AccountProfileManager.validate(username, displayName)?.let { viewModel.showError(it); return }
        AuthManager.createEmailAccount(email, password,
            onSuccess = {
                authAccount = AuthManager.account
                lifecycleScope.launch {
                    try {
                        val profile = AccountProfileManager.claim(username, displayName)
                        val identity = appContainer.identityFor(authAccount.uid)
                        val saved = identity.getProfile().copy(
                            displayName = profile.displayName, username = profile.username,
                        )
                        identity.saveProfile(saved)
                        runCatching { privateProfileStore.save(authAccount.uid,
                            PrivateProfileSnapshot(saved, identity.profileUpdatedAt())) }
                        AuthManager.sendVerificationEmail { }
                        restartForAccountChange()
                    } catch (error: Exception) {
                        intent.putExtra(ACCOUNT_SETUP_ERROR,
                            error.localizedMessage ?: "Account created, but username setup failed. Try another.")
                        restartForAccountChange()
                    }
                }
            },
            onError = viewModel::showError,
        )
    }

    private fun signOut() {
        viewModel.stopChat()
        AuthManager.signOut()
        authAccount = AuthAccount()
        accountProfile = null
        lifecycleScope.launch {
            runCatching {
                CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest())
            }
            restartForAccountChange()
        }
    }

    private fun restartForAccountChange() {
        viewModelStore.clear()
        recreate()
    }

    companion object {
        private const val ACCOUNT_SETUP_ERROR = "account_setup_error"
    }

    private fun createEmailAccount(email: String, password: String) {
        AuthManager.createEmailAccount(email, password,
            onSuccess = {
                authAccount = AuthManager.account
                AuthManager.sendVerificationEmail { restartForAccountChange() }
            },
            onError = viewModel::showError,
        )
    }

    private fun signInWithEmail(email: String, password: String) {
        AuthManager.signInWithEmail(email, password,
            onSuccess = ::restartForAccountChange,
            onError = viewModel::showError,
        )
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
                AuthManager.signInWithGoogleToken(token,
                    onSuccess = ::restartForAccountChange,
                    onError = viewModel::showError,
                )
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
        if (authAccount.uid.isNotBlank() && !authAccount.emailVerified) {
            AuthManager.refreshAccount {
                authAccount = AuthManager.account
                viewModel.accountChanged(authAccount.uid)
            }
        }
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
            viewModel.requestEventAdminAccess()
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
                    viewModel.enterEventWithGps(
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
                else viewModel.enterEventWithQr(payload)
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
            viewModel.importDeviceContacts(DeviceContactsReader.read(applicationContext))
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
                else viewModel.importScannedContactCard(payload)
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
        AuthManager.ensureSignedIn { success ->
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
