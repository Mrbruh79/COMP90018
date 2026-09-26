package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneIdentityTest {
    @Test
    fun formattingDifferencesProduceTheSameIdentity() {
        val plain = PhoneIdentity.hash("+1 555 123 4567")
        val formatted = PhoneIdentity.hash("(1555) 123-4567")

        assertNotNull(plain)
        assertEquals(plain, formatted)
    }

    @Test
    fun invalidPhoneNumberIsRejected() {
        assertNull(PhoneIdentity.normalize("123"))
        assertNull(PhoneIdentity.normalizeInternational("0412345678"))
    }

    @Test
    fun australianTrunkZeroMatchesInternationalMobileNumber() {
        assertEquals("+61412345678", PhoneIdentity.normalizeInternational("+61 0412 345 678"))
        assertEquals(PhoneIdentity.hash("+61 0412 345 678"), PhoneIdentity.hash("+61412345678"))
    }
}
