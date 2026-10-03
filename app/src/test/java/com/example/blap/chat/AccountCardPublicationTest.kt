package com.example.blap.chat

import org.junit.Assert.*
import org.junit.Test

class AccountCardPublicationTest {
    private val profile = ContactProfile("Alice", username = "alice")

    @Test fun profileUpdatesKeepThePublishedKeyForTheSameDevice() {
        val fields = AccountCardPublication.fields("alice-uid", profile, "alice-peer", "alice-peer", "existing-key")
        assertEquals("existing-key", fields["nearbyPublicKey"])
        assertEquals("alice", fields["username"])
    }

    @Test fun switchingDevicesDoesNotAttachThePreviousDevicesKey() {
        val fields = AccountCardPublication.fields("alice-uid", profile, "new-peer", "old-peer", "old-key")
        assertFalse(fields.containsKey("nearbyPublicKey"))
        assertEquals("new-peer", fields["peerId"])
    }

    @Test fun aFirstPublicationUsesOnlyPublicIdentityFields() {
        val fields = AccountCardPublication.fields("alice-uid", profile.copy(phoneNumber = "+12025550198",
            email = "alice@example.com"), "alice-peer", "", "")
        assertEquals(setOf("uid", "name", "peerId", "username"), fields.keys)
    }
}
