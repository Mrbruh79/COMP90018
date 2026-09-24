package com.example.blap.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.blap.BlapApplication
import com.example.blap.chat.NearbyChatManager
import com.example.blap.notifications.MessageNotificationManager

class NearbyMessagingService : Service() {
    private lateinit var session: NearbyServiceSession
    private lateinit var boundary: NearbyServiceBoundary

    override fun onCreate() {
        super.onCreate()
        val app = application as BlapApplication
        session = NearbyServiceSession(app.chatCoordinator, NearbyChatManager(this)) {
            boundary.stopSafely()
        }
        boundary = NearbyServiceBoundary(
            NearbyServiceTeardown(
                stopSession = session::stop,
                removeForeground = {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                },
                stopService = { stopSelf() },
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            boundary.stopSafely()
            return START_NOT_STICKY
        }
        val app = application as BlapApplication
        try {
            ServiceCompat.startForeground(
                this,
                MessageNotificationManager.SERVICE_NOTIFICATION_ID,
                app.messageNotifications.serviceNotification(),
                foregroundServiceType(Build.VERSION.SDK_INT),
            )
        } catch (exception: RuntimeException) {
            app.chatCoordinator.showError(
                exception.message ?: "Could not keep Nearby messaging active.",
            )
            boundary.stopSafely()
            return START_NOT_STICKY
        }
        boundary.startSafely(
            startSession = session::start,
            reportError = app.chatCoordinator::showError,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        boundary.stopSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "com.example.blap.START_NEARBY"
        private const val ACTION_STOP = "com.example.blap.STOP_NEARBY"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearbyMessagingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NearbyMessagingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}

@SuppressLint("InlinedApi")
internal fun foregroundServiceType(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    } else {
        0
    }

internal class NearbyServiceTeardown(
    private val stopSession: () -> Unit,
    private val removeForeground: () -> Unit,
    private val stopService: () -> Unit,
) {
    private var stopped = false

    fun stop() {
        if (stopped) return
        stopped = true
        try {
            stopSession()
        } finally {
            try {
                removeForeground()
            } finally {
                stopService()
            }
        }
    }
}

internal class NearbyServiceBoundary(
    private val teardown: NearbyServiceTeardown,
) {
    fun startSafely(
        startSession: () -> Boolean,
        reportError: (String) -> Unit,
    ): Boolean {
        val started = try {
            startSession()
        } catch (exception: RuntimeException) {
            runCatching {
                reportError(exception.message ?: "Could not start Nearby messaging.")
            }
            false
        }
        if (!started) stopSafely()
        return started
    }

    fun stopSafely() {
        runCatching { teardown.stop() }
    }
}
