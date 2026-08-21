package com.example.blap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.blap.chat.ChatViewModel
import com.example.blap.ui.NearbyChatApp
import com.example.blap.ui.NearbyChatTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(applicationContext)
    }

    private var deniedPermissions by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = NearbyPermissions.missing(this)
        deniedPermissions = missing
        if (missing.isEmpty()) viewModel.startChat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NearbyChatTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                NearbyChatApp(
                    uiState = uiState,
                    deniedPermissions = deniedPermissions.map(NearbyPermissions::displayName),
                    onNameChanged = viewModel::updateDisplayName,
                    onStartChat = ::requestPermissionsAndStart,
                    onConnect = viewModel::connectToDevice,
                    onSendMessage = viewModel::sendMessage,
                    onDisconnect = viewModel::disconnect,
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = ::openAppSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (deniedPermissions.isNotEmpty()) {
            deniedPermissions = NearbyPermissions.missing(this)
        }
    }

    private fun requestPermissionsAndStart() {
        val missing = NearbyPermissions.missing(this)
        if (missing.isEmpty()) {
            deniedPermissions = emptyList()
            viewModel.startChat()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
