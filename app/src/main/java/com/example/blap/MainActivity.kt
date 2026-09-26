package com.example.blap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.credentials.CredentialManager
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
import com.example.blap.chat.ChatViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.example.blap.ui.NearbyChatApp
import com.example.blap.ui.screens.onboarding.OnboardingPreferences
import com.example.blap.ui.screens.onboarding.OnboardingScreen
import com.example.blap.ui.theme.CommonGroundTheme
import com.example.blap.venue.VenueManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(applicationContext)
    }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())
    private var pendingEventGpsEntry = false
    private var pendingEventQrScan = false
    private var authAccount by mutableStateOf(AuthAccount())

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
            }
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

    private val eventLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (VenuePermissions.missing(this).isEmpty()) checkEventLocation()
        else {
            pendingEventGpsEntry = false
            viewModel.showError("Allow location access or use the venue check-in QR.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        authAccount = AuthManager.account
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
                    onMessageDraftChanged = viewModel::updateMessageDraft,
                    onDisconnect = viewModel::disconnect,
                    onBeginCreateGroup = viewModel::beginCreateGroup,
                    onGroupNameChanged = viewModel::updateGroupName,
                    onToggleGroupMember = viewModel::toggleGroupMember,
                    onCreateGroup = viewModel::createPrivateGroup,
                    onManageContacts = viewModel::beginManageContacts,
                    onBeginAddContact = viewModel::beginAddContact,
                    onOpenContact = viewModel::openContact,
                    onMessageContact = viewModel::messageContact,
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
                        )
                    },
                    onOpenEvent = viewModel::openEvent,
                    onUpdateEvent = viewModel::updateSelectedEvent,
                    onDeleteEvent = viewModel::deleteSelectedEvent,
                    onJoinEvent = viewModel::joinSelectedEvent,
                    onLeaveEvent = viewModel::leaveSelectedEvent,
                    onPromoteEventMember = viewModel::promoteEventMember,
                    onRemoveEventMember = viewModel::removeEventMember,
                    onDeleteEventData = viewModel::deleteSelectedEventData,
                    onShowEventAnnouncements = viewModel::showEventAnnouncements,
                    onPublishEventAnnouncement = viewModel::publishEventAnnouncement,
                    onRequestEventGpsEntry = ::requestEventGpsEntry,
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
    }

    private fun createEmailAccount(email: String, password: String) {
        AuthManager.createEmailAccount(email, password,
            onSuccess = {
                authAccount = AuthManager.account
                viewModel.accountChanged(authAccount.uid)
                AuthManager.sendVerificationEmail { sent ->
                    viewModel.showNotice(if (sent) "Account created. Check your email to enable email lookup."
                        else "Account created. Verify your email to enable email lookup.")
                }
            },
            onError = viewModel::showError,
        )
    }

    private fun signInWithEmail(email: String, password: String) {
        AuthManager.signInWithEmail(email, password,
            onSuccess = {
                authAccount = AuthManager.account
                viewModel.accountChanged(authAccount.uid)
                viewModel.showNotice("Signed in with email.")
            },
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
                    onSuccess = {
                        authAccount = AuthManager.account
                        viewModel.accountChanged(authAccount.uid)
                        viewModel.showNotice("Signed in with Google.")
                    },
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
        if (deniedPermissions.isNotEmpty()) deniedPermissions = NearbyPermissions.missing(this)
        if (authAccount.uid.isNotBlank() && !authAccount.emailVerified) {
            AuthManager.refreshAccount {
                authAccount = AuthManager.account
                viewModel.accountChanged(authAccount.uid)
            }
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
        val missingNearby = NearbyPermissions.missing(this)
        if (missingNearby.isEmpty()) {
            viewModel.startChat()
            requestEventLocationPermission()
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
                val location = VenueManager.getFreshLocation(applicationContext)
                if (location == null) {
                    viewModel.showError("A current location was not available. Use the venue check-in QR.")
                } else {
                    viewModel.enterEventWithGps(
                        location.latitude,
                        location.longitude,
                        location.accuracy.toDouble(),
                    )
                }
            } catch (_: Exception) {
                viewModel.showError("Location could not be checked. Use the venue check-in QR.")
            } finally {
                pendingEventGpsEntry = false
            }
        }
    }

    private fun requestEventQrScan() {
        pendingEventQrScan = true
        pendingEventGpsEntry = false
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
                val venue = VenueManager.findNearbyVenue(applicationContext)
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
