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
    }
}
