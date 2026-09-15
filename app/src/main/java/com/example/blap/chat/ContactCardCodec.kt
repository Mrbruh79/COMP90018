package com.example.blap.chat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A deliberately small, versioned payload for contact-card QR codes. */
object ContactCardCodec {
    private const val PREFIX = "BLAP-CONTACT:1:"

    fun encode(profile: ContactProfile): String = PREFIX + listOf(
        profile.displayName,
        profile.phoneNumber,
        profile.email,
        profile.bio,
        profile.websiteUrl,
        profile.instagramUrl,
        profile.xUrl,
        profile.linkedinUrl,
        profile.githubUrl,
    ).joinToString("|") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

    fun decode(payload: String): ContactProfile? {
        if (!payload.startsWith(PREFIX)) return null
        return runCatching {
            val fields = payload.removePrefix(PREFIX).split('|')
            if (fields.size != 9) return null
            val decoded = fields.map {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            }
            ContactProfile(
                displayName = decoded[0].take(24),
                phoneNumber = decoded[1].take(24),
                email = decoded[2].take(120),
                bio = decoded[3].take(240),
                websiteUrl = decoded[4].take(200),
                instagramUrl = decoded[5].take(200),
                xUrl = decoded[6].take(200),
                linkedinUrl = decoded[7].take(200),
                githubUrl = decoded[8].take(200),
            )
        }.getOrNull()
    }
}
