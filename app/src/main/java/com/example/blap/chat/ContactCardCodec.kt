package com.example.blap.chat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A deliberately small, versioned payload for contact-card QR codes. */
object ContactCardCodec {
    private const val PREFIX = "BLAP-CONTACT:1:"
    private const val PAIRED_PREFIX = "BLAP-CONTACT:2:"
    private const val MULTI_ADDRESS_PREFIX = "BLAP-CONTACT:3:"

    data class Card(val profile: ContactProfile, val peerId: String = "")

    fun encode(profile: ContactProfile, peerId: String = ""): String =
        MULTI_ADDRESS_PREFIX + listOf(
        profile.displayName,
        profile.phoneNumber,
        profile.email,
        profile.googleAccountEmail,
        profile.bio,
        profile.websiteUrl,
        profile.instagramUrl,
        profile.xUrl,
        profile.linkedinUrl,
        profile.githubUrl,
        peerId,
    )
            .joinToString("|") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

    fun decode(payload: String): ContactProfile? = decodeCard(payload)?.profile

    fun decodeCard(payload: String): Card? {
        val multiAddress = payload.startsWith(MULTI_ADDRESS_PREFIX)
        val paired = payload.startsWith(PAIRED_PREFIX)
        if (!multiAddress && !paired && !payload.startsWith(PREFIX)) return null
        return runCatching {
            val fields = payload.removePrefix(
                if (multiAddress) MULTI_ADDRESS_PREFIX else if (paired) PAIRED_PREFIX else PREFIX,
            ).split('|')
            if (fields.size != if (multiAddress) 11 else if (paired) 10 else 9) return null
            val decoded = fields.map {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            }
            val peerId = if (multiAddress) decoded[10] else if (paired) decoded[9] else ""
            if (peerId.isNotBlank() && runCatching { UUID.fromString(peerId).toString() }.getOrNull() != peerId) {
                return null
            }
            val offset = if (multiAddress) 1 else 0
            Card(ContactProfile(
                displayName = decoded[0].take(24),
                phoneNumber = decoded[1].take(24),
                email = decoded[2].take(120),
                googleAccountEmail = if (multiAddress) decoded[3].take(120) else "",
                bio = decoded[3 + offset].take(240),
                websiteUrl = decoded[4 + offset].take(200),
                instagramUrl = decoded[5 + offset].take(200),
                xUrl = decoded[6 + offset].take(200),
                linkedinUrl = decoded[7 + offset].take(200),
                githubUrl = decoded[8 + offset].take(200),
            ), peerId = peerId.take(100))
        }.getOrNull()
    }
}
