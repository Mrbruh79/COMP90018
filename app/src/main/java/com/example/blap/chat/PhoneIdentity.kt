package com.example.blap.chat

import java.security.MessageDigest

object PhoneIdentity {
    fun normalize(number: String): String? {
        val allDigits = number.filter(Char::isDigit)
        val digits = if (number.trim().startsWith("00")) allDigits.drop(2) else allDigits
        if (digits.length !in 8..15) return null
        return "+$digits"
    }

    fun hash(number: String): String? {
        val normalized = normalize(number) ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
