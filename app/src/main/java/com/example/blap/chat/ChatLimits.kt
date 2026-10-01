package com.example.blap.chat

object ChatLimits {
    const val MAX_NAME_LENGTH = 24
    const val MAX_MESSAGE_LENGTH = 1_000
    const val MAX_VOICE_ENCODED_LENGTH = VoiceNoteLimits.MAX_ENCODED_LENGTH
    const val MIN_VOICE_DURATION_MS = VoiceNoteLimits.MIN_DURATION_MS
    const val MAX_VOICE_DURATION_MS = VoiceNoteLimits.MAX_DURATION_MS
    const val MAX_GROUP_NAME_LENGTH = 40
    const val MAX_PHONE_LENGTH = 24
    const val MAX_EMAIL_LENGTH = 120
    const val MAX_BIO_LENGTH = 240
    const val MAX_URL_LENGTH = 200

}
