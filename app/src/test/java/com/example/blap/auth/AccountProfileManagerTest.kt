package com.example.blap.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountProfileManagerTest {
    @Test fun normalizesUsernamesForCaseInsensitiveReservations() {
        assertEquals("alice_1", AccountProfileManager.normalizeUsername(" Alice_1 "))
        assertNull(AccountProfileManager.validate("Alice_1", "Alice"))
    }

    @Test fun rejectsInvalidUsernamesAndDisplayNames() {
        assertNotNull(AccountProfileManager.validate("ab", "Alice"))
        assertNotNull(AccountProfileManager.validate("has space", "Alice"))
        assertNotNull(AccountProfileManager.validate("alice", " "))
    }
}
