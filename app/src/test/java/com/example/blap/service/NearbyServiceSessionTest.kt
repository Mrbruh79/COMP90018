package com.example.blap.service

import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.FakeChatStore
import com.example.blap.chat.FakeIdentityStore
import com.example.blap.chat.FakeNearbyChatController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
