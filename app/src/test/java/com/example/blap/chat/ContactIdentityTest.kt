package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactIdentityTest {
    @Test
    fun emailAddressIsNormalizedForContactMatching() {
        assertEquals("alex@example.com", ContactIdentity.normalizeEmail(" Alex@Example.COM "))
        assertNull(ContactIdentity.normalizeEmail("not an email"))
    }

    @Test
    fun phoneTakesPriorityAndEmailOnlyHasStablePrivateLocalId() {
        assertEquals("phone:hash", ContactIdentity.localPeerId("hash", "alex@example.com", ""))
        val emailId = ContactIdentity.localPeerId("", "Alex@Example.com", "")
        assertEquals(emailId, ContactIdentity.localPeerId("", "", "alex@example.com"))
        assertNotEquals("email:alex@example.com", emailId)
    }
}
