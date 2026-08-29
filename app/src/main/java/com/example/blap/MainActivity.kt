package com.example.blap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.example.blap.ui.NearbyChatApp
import com.example.blap.ui.NearbyChatTheme
import com.example.blap.venue.VenueManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(applicationContext)
    }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())

    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = NearbyPermissions.missing(this)
        deniedPermissions = missing
        if (missing.isEmpty()) viewModel.startChat()
    }

    private val venuePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (VenuePermissions.missing(this).isEmpty()) checkForNearbyVenue()
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) importDeviceContacts()
        else viewModel.showError("Contacts permission is needed to import device contacts.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AuthManager.ensureSignedIn { success ->
            if (success) requestVenuePermissionAndCheck()
            else Log.w("FirebaseAuth", "Anonymous sign in failed")
        }

        setContent {
            NearbyChatTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NearbyChatApp(
                    uiState = uiState,
                    deniedPermissions = deniedPermissions.map(NearbyPermissions::displayName),
                    onNameChanged = viewModel::updateDisplayName,
                    onPhoneChanged = viewModel::updatePhoneNumber,
                    onStartChat = ::requestNearbyPermissionsAndStart,
                    onConnect = viewModel::connectToDevice,
                    onOpenConversation = viewModel::openConversation,
                    onBackToChats = viewModel::showConversationList,
                    onSendMessage = viewModel::sendMessage,
                    onDisconnect = viewModel::disconnect,
                    onBeginCreateGroup = viewModel::beginCreateGroup,
                    onGroupNameChanged = viewModel::updateGroupName,
                    onToggleGroupMember = viewModel::toggleGroupMember,
                    onCreateGroup = viewModel::createPrivateGroup,
                    onManageContacts = viewModel::beginManageContacts,
                    onBeginAddContact = viewModel::beginAddContact,
                    onOpenContact = viewModel::openContact,
                    onContactDraftChanged = viewModel::updateContactDraft,
                    onDeleteContact = viewModel::deleteContact,
                    onScanContact = ::scanContactCard,
                    onSaveContact = viewModel::saveContact,
                    onImportContacts = ::requestContactImport,
                    onShowMyCard = viewModel::showMyCard,
                    onEditProfile = viewModel::editProfile,
                    onProfileChanged = viewModel::updateProfile,
                    onSaveProfile = viewModel::saveProfile,
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
    }

    override fun onResume() {
        super.onResume()
        if (deniedPermissions.isNotEmpty()) deniedPermissions = NearbyPermissions.missing(this)
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
        lifecycleScope.launch {
            val result = try {
                val venue = VenueManager.findNearbyVenue(applicationContext)
                if (venue == null) "No venue nearby" else "Checked in: ${venue.name}"
            } catch (exception: Exception) {
                "Check-in failed: ${exception.message}"
            }
            Log.d("VenueCheck", result)
        }
    }

    private fun requestVenuePermissionAndCheck() {
        val missing = VenuePermissions.missing(this)
        if (missing.isEmpty()) checkForNearbyVenue()
        else venuePermissionLauncher.launch(missing.toTypedArray())
    }
}
