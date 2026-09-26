package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactCardCodecTest {
    @Test
    fun contactCardRoundTripsSpecialCharacters() {
        val profile = ContactProfile(
            displayName = "Ari | Chen",
            phoneNumber = "+61 400 000 000",
            email = "ari+blap@example.com",
            googleAccountEmail = "ari.google@example.com",
            bio = "Designer & builder — nearby first.",
            websiteUrl = "https://example.com/about?from=blap",
            instagramUrl = "https://instagram.com/ari",
            xUrl = "https://x.com/ari",
            linkedinUrl = "https://linkedin.com/in/ari",
            githubUrl = "https://github.com/ari",
        )

        assertEquals(profile, ContactCardCodec.decode(ContactCardCodec.encode(profile)))
    }

    @Test
    fun arbitraryQrIsNotAcceptedAsContactCard() {
        assertNull(ContactCardCodec.decode("https://example.com"))
    }

    @Test
    fun pairedCardKeepsMeshPeerIdentityAndPhone() {
        val profile = ContactProfile(displayName = "Bob", phoneNumber = "+12025550198")
        val peerId = "20aecc56-8f17-44b1-ac58-338417b7d320"
        val decoded = ContactCardCodec.decodeCard(ContactCardCodec.encode(profile, peerId))

        assertEquals(profile, decoded?.profile)
        assertEquals(peerId, decoded?.peerId)
    }

    @Test
    fun olderPairedCardsStillDecodeWithoutGoogleEmail() {
        val peerId = "20aecc56-8f17-44b1-ac58-338417b7d320"
        val payload = "BLAP-CONTACT:2:Bob|%2B12025550198||||||||$peerId"
        val decoded = ContactCardCodec.decodeCard(payload)

        assertEquals("Bob", decoded?.profile?.displayName)
        assertEquals("", decoded?.profile?.googleAccountEmail)
        assertEquals(peerId, decoded?.peerId)
    }
}
