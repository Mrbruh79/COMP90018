package com.example.blap.chat

import java.security.MessageDigest
import java.util.Locale

object ContactIdentity {
    fun conversationId(contact: SavedContact): String? =
        contact.cloudUserId.takeIf(String::isNotBlank)?.let { "account:$it" }
            ?: contact.linkedPeerId ?: localPeerId(contact.phoneHash, contact.email, contact.googleAccountEmail)

    fun aliases(contact: SavedContact): Set<String> = listOfNotNull(
        conversationId(contact), contact.linkedPeerId,
        localPeerId(contact.phoneHash, "", ""),
        localPeerId("", contact.email, ""),
        localPeerId("", "", contact.googleAccountEmail),
        "contact:${contact.id}",
    ).filter(String::isNotBlank).toSet()

    fun normalizeEmail(value: String): String? {
        val email = value.trim().lowercase(Locale.ROOT)
        if (email.length !in 3..120 || email.any(Char::isWhitespace)) return null
        val parts = email.split('@')
        if (parts.size != 2 || parts[0].isBlank() || !parts[1].contains('.') ||
            parts[1].startsWith('.') || parts[1].endsWith('.')
        ) return null
        return email
    }

    fun localPeerId(phoneHash: String, email: String, googleAccountEmail: String): String? {
        if (phoneHash.isNotBlank()) return "phone:$phoneHash"
        val address = normalizeEmail(email) ?: normalizeEmail(googleAccountEmail) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "email:$digest"
    }
}
