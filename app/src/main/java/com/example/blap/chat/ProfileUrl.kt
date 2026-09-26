package com.example.blap.chat

object ProfileUrl {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (!isOpenable(trimmed)) return trimmed
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    fun isOpenable(value: String) =
        value.isNotBlank() && value.none(Char::isWhitespace) && value.contains('.')
}
