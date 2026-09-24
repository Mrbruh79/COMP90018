package com.example.blap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.example.blap.auth.AuthManager
import com.example.blap.chat.ChatViewModel
import com.example.blap.notifications.MessageNotificationManager
import com.example.blap.service.NearbyMessagingService
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.example.blap.ui.NearbyChatApp
import com.example.blap.ui.theme.CommonGroundTheme
import com.example.blap.venue.VenueManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(application as BlapApplication)
    }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = NearbyPermissions.missing(this)
        deniedPermissions = missing
        if (missing.isEmpty()) requestNotificationsAndStartNearby()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        startNearbyService()
        if (!granted) {
            viewModel.showNotice("Notifications are off. New messages will still appear in BLAP.")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            CommonGroundTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NearbyChatApp(
                    uiState = uiState,
                    deniedPermissions = deniedPermissions.map(NearbyPermissions::displayName),
                    onNameChanged = viewModel::updateDisplayName,
                    onPhoneChanged = viewModel::updatePhoneNumber,
                    onStartChat = ::requestNearbyPermissionsAndStart,
                    onCompleteSetup = viewModel::completeSetup,
                    onStopChat = { NearbyMessagingService.stop(this) },
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
                    onConversationSearchChanged = viewModel::updateConversationSearch,
                    onContactSearchChanged = viewModel::updateContactSearch,
                    onBeginGroupSettings = viewModel::beginGroupSettings,
                    onSaveGroupSettings = viewModel::saveGroupSettings,
                    onSystemBack = viewModel::handleBack,
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = ::openAppSettings,
                )
            }
        }

        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as BlapApplication).conversationVisibility.setAppForeground(true)
    }

    override fun onStop() {
        (application as BlapApplication).conversationVisibility.setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (deniedPermissions.isNotEmpty()) deniedPermissions = NearbyPermissions.missing(this)
    }

    private fun requestNearbyPermissionsAndStart() {
        val missing = NearbyPermissions.missing(this)
        if (missing.isEmpty()) {
            deniedPermissions = emptyList()
            requestNotificationsAndStartNearby()
        } else {
            nearbyPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestNotificationsAndStartNearby() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startNearbyService()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startNearbyService() {
        try {
            NearbyMessagingService.start(this)
        } catch (exception: RuntimeException) {
            viewModel.showError(exception.message ?: "Could not keep Nearby messaging active.")
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != MessageNotificationManager.ACTION_OPEN_CONVERSATION) return
        val conversationId = intent.getStringExtra(
            MessageNotificationManager.EXTRA_CONVERSATION_ID,
        ) ?: return
        viewModel.openConversationFromNotification(conversationId)
        intent.action = null
        intent.removeExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
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
