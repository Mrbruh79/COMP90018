package com.example.blap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.blap.application.ApplicationViewModels
import com.example.blap.platform.AndroidPlatformBridge
import com.example.blap.ui.ApplicationRoute
import com.example.blap.ui.theme.CommonGroundTheme
import kotlinx.coroutines.launch

/** Hosts Compose and Activity-bound Android operations. Application decisions belong to ViewModels. */
class MainActivity : ComponentActivity() {
    private val models by lazy {
        ApplicationViewModels.obtain(this, (application as BlapApplication).appContainer)
    }
    private lateinit var platform: AndroidPlatformBridge
    private var restartingAccount = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        platform = AndroidPlatformBridge(this, models.application, savedInstanceState)
        models.auth.initialize()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { models.application.platformRequests.collect(platform::launch) }
                launch {
                    models.auth.accountChanges.collect { change ->
                        if (restartingAccount) return@collect
                        models.application.freezeForAccountChange()
                        change.setupError?.let { intent.putExtra(ACCOUNT_SETUP_ERROR, it) }
                        if (change.clearCredentials) platform.clearCredentialState()
                        restartingAccount = true
                        viewModelStore.clear()
                        recreate()
                    }
                }
            }
        }
        setContent { CommonGroundTheme { ApplicationRoute(models) } }
        intent.getStringExtra(ACCOUNT_SETUP_ERROR)?.let {
            intent.removeExtra(ACCOUNT_SETUP_ERROR)
            models.chat.showError(it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!restartingAccount) models.application.onResume(platform.permissionSnapshot())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        platform.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val ACCOUNT_SETUP_ERROR = "account_setup_error"
    }
}
