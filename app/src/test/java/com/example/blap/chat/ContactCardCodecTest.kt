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
}
