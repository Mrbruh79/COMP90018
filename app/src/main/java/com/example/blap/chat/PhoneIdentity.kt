package com.example.blap.chat

import java.security.MessageDigest
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat

object PhoneIdentity {
    fun normalizeInternational(number: String): String? {
        val value = number.trim()
        if (!value.startsWith("+") && !value.startsWith("00")) return null
        return normalize(value)
    }

    fun normalize(number: String): String? {
        val allDigits = number.filter(Char::isDigit)
        val digits = if (number.trim().startsWith("00")) allDigits.drop(2) else allDigits
        if (digits.length !in 8..15) return null
        val international = "+$digits"
        if (!number.trim().startsWith("+") && !number.trim().startsWith("00")) return international
        return runCatching {
            val utility = PhoneNumberUtil.getInstance()
            utility.format(utility.parse(international, "ZZ"), PhoneNumberFormat.E164)
        }.getOrNull()?.takeIf { it.length in 9..16 }
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
