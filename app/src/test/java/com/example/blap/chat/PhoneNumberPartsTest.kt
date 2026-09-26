package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberPartsTest {
    @Test fun splitsAndRejoinsAustralianNumber() {
        val parts = PhoneNumberParts.from("+61412345678", "AU")
        assertEquals("61", parts.countryCode)
        assertEquals("412345678", parts.nationalNumber)
        assertEquals("+61412345678", parts.combined())
    }

    @Test fun emptyNumberUsesRegionCallingCode() {
        val parts = PhoneNumberParts.from("", "GB")
        assertEquals("44", parts.countryCode)
        assertEquals("", parts.nationalNumber)
        assertEquals("", parts.combined())
    }

    @Test fun preservesLeadingZeroWhenRequired() {
        val parts = PhoneNumberParts.from("+390212345678", "IT")
        assertEquals("39", parts.countryCode)
        assertEquals("0212345678", parts.nationalNumber)
        assertEquals("+390212345678", parts.combined())
    }

    @Test fun rejectsUnknownCallingCodeOnSave() {
        assertNull(PhoneIdentity.normalizeInternational("+999123456789"))
    }
}
