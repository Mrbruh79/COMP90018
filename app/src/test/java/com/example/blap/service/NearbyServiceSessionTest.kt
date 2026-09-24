package com.example.blap.service

import android.content.pm.ServiceInfo
import android.os.Build
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.FakeChatStore
import com.example.blap.chat.FakeIdentityStore
import com.example.blap.chat.FakeNearbyChatController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyServiceSessionTest {
    @Test
    fun repeatedStartAndStopOwnOneNearbySession() {
        val controller = FakeNearbyChatController()
        val coordinator = ChatCoordinator(
            chatStore = FakeChatStore(),
            identityStore = FakeIdentityStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        coordinator.updateDisplayName("Alice")
        val session = NearbyServiceSession(coordinator, controller)

        assertTrue(session.start())
        assertTrue(session.start())
        assertEquals(1, controller.startAdvertisingCalls)

        session.stop()
        session.stop()
        assertEquals(1, controller.closeCalls)
        assertFalse(coordinator.uiState.value.nearbyActive)
    }

    @Test
    fun nearbyStartupFailureRequestsServiceStop() {
        val controller = FakeNearbyChatController()
        val coordinator = ChatCoordinator(
            chatStore = FakeChatStore(),
            identityStore = FakeIdentityStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        coordinator.updateDisplayName("Alice")
        var stopRequests = 0
        val session = NearbyServiceSession(coordinator, controller) {
            stopRequests++
        }

        assertTrue(session.start())
        controller.listener?.onNearbyUnavailable("Advertising failed")

        assertEquals(1, stopRequests)
        assertFalse(coordinator.uiState.value.nearbyActive)
        assertEquals("Advertising failed", coordinator.uiState.value.error)
    }

    @Test
    fun stopDetachesAndClosesWhenCoordinatorStopFails() {
        val controller = FakeNearbyChatController()
        val coordinator = ChatCoordinator(
            chatStore = FakeChatStore(),
            identityStore = FakeIdentityStore(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        coordinator.updateDisplayName("Alice")
        val session = NearbyServiceSession(coordinator, controller)
        assertTrue(session.start())
        val failure = IllegalStateException("stop failed")
        controller.stopFailure = failure

        val thrown = runCatching { session.stop() }.exceptionOrNull()
        session.stop()

        assertSame(failure, thrown)
        assertFalse(coordinator.uiState.value.nearbyActive)
        assertNull(controller.listener)
        assertEquals(1, controller.closeCalls)
    }

    @Test
    fun serviceTeardownRemovesForegroundAndStopsSelfWhenSessionStopFails() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("session stop failed")
        val teardown = NearbyServiceTeardown(
            stopSession = {
                calls += "session"
                throw failure
            },
            removeForeground = { calls += "foreground" },
            stopService = { calls += "service" },
        )

        val thrown = runCatching { teardown.stop() }.exceptionOrNull()
        teardown.stop()

        assertSame(failure, thrown)
        assertEquals(listOf("session", "foreground", "service"), calls)
    }

    @Test
    fun serviceBoundarySuppressesForegroundRemovalFailureAfterStoppingSelf() {
        val calls = mutableListOf<String>()
        val teardown = NearbyServiceTeardown(
            stopSession = { calls += "session" },
            removeForeground = {
                calls += "foreground"
                throw IllegalStateException("foreground removal failed")
            },
            stopService = { calls += "service" },
        )
        val boundary = NearbyServiceBoundary(teardown)

        val thrown = runCatching { boundary.stopSafely() }.exceptionOrNull()
        boundary.stopSafely()

        assertNull(thrown)
        assertEquals(listOf("session", "foreground", "service"), calls)
    }

    @Test
    fun serviceBoundaryReportsSynchronousStartupFailureAndTearsDown() {
        val calls = mutableListOf<String>()
        val teardown = NearbyServiceTeardown(
            stopSession = { calls += "session" },
            removeForeground = { calls += "foreground" },
            stopService = { calls += "service" },
        )
        val boundary = NearbyServiceBoundary(teardown)
        var reportedError: String? = null

        val started = boundary.startSafely(
            startSession = { throw SecurityException("Nearby permission revoked") },
            reportError = { reportedError = it },
        )

        assertFalse(started)
        assertEquals("Nearby permission revoked", reportedError)
        assertEquals(listOf("session", "foreground", "service"), calls)
    }

    @Test
    fun connectedDeviceForegroundTypeIsUsedFromApi29() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            foregroundServiceType(Build.VERSION_CODES.Q),
        )
        assertEquals(0, foregroundServiceType(Build.VERSION_CODES.Q - 1))
    }
}
