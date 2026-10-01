package com.example.blap.event

internal fun Throwable.readableEventMessage(prefix: String): String =
    "$prefix: ${localizedMessage?.takeIf(String::isNotBlank) ?: "unknown error"}"
