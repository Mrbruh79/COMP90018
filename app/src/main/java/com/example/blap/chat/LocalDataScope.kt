package com.example.blap.chat

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/** Keeps each account's chats, contacts and identity in its own local files. */
object LocalDataScope {
    fun forAccount(context: Context, uid: String): String {
        val preferences = context.getSharedPreferences("local_data_owner", Context.MODE_PRIVATE)
        if (uid.isBlank()) {
            if (preferences.getString("original_owner_uid", null) == null &&
                context.getDatabasePath("nearby_chat.db").exists()) {
                // The old database has no recorded owner. Never hand it to the next login.
                preferences.edit { putString("original_owner_uid", "__unclaimed_legacy__") }
            }
            return "_signed_out"
        }
        val originalOwner = preferences.getString("original_owner_uid", null)
        if (originalOwner == null) {
            preferences.edit { putString("original_owner_uid", uid) }
            return ""
        }
        return if (originalOwner == uid) "" else {
            val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "_$digest"
        }
    }
}
