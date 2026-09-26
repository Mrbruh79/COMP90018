package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountLookupTest {
    @Test fun onlineLookupUsesOptedInDiscoveryNumberNotVisibleCardNumber() {
        val profile = ContactProfile(
            phoneNumber = "+12025550198",
            lookupPhoneNumber = "+61 0412 345 678",
            discoverableByPhone = true,
        )
        assertEquals(PhoneIdentity.hash("+61412345678"), AccountLookup.phoneHash(profile))
        assertEquals("", AccountLookup.phoneHash(profile.copy(discoverableByPhone = false)))
    }
}
