    package com.example.blap

    import android.content.Intent
    import android.net.Uri
    import android.os.Bundle
    import android.provider.Settings
    import android.util.Log
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.enableEdgeToEdge
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.activity.viewModels
    import androidx.compose.runtime.LaunchedEffect
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.setValue
    import androidx.lifecycle.compose.collectAsStateWithLifecycle
    import androidx.lifecycle.lifecycleScope
    import com.example.blap.auth.AuthManager
    import com.example.blap.chat.ChatViewModel
    import com.example.blap.ui.NearbyChatApp
    import com.example.blap.ui.NearbyChatTheme
    import com.example.blap.ui.screens.onboarding.SplashScreen
    import com.example.blap.ui.theme.CommonGroundTheme
    import com.example.blap.venue.VenueManager
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.launch

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

        private val venuePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val missing = VenuePermissions.missing(this)
            if (missing.isEmpty()) checkForNearbyVenue()
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            AuthManager.ensureSignedIn { success ->                                                 // TODO: handle sign-in failure (e.g. retry or show an error)
                // placeholder for now
            }
            requestVenuePermissionAndCheck()
            enableEdgeToEdge()

            setContent {
                // Temporary: shows the brand moment, then falls through to the existing
                // flow unchanged. Replace with real navigation once Sign-in/Home exist.
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(1200)
                    showSplash = false
                }

                if (showSplash) {
                    CommonGroundTheme {
                        SplashScreen()
                    }
                } else {
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

        private fun checkForNearbyVenue() {
            lifecycleScope.launch {
                val message = try {
                    val venue = VenueManager.findNearbyVenue(applicationContext)
                    if (venue != null) "Checked in: ${venue.name}" else "No venue nearby"
                } catch (e: Exception) {
                    "Check-in failed: ${e.message}"
                }
                Log.d("VenueDebug", message)
            }
        }

        private fun requestVenuePermissionAndCheck() {
            val missing = VenuePermissions.missing(this)
            if (missing.isEmpty()) {
                checkForNearbyVenue()
            } else {
                venuePermissionLauncher.launch(missing.toTypedArray())
            }
        }

    }