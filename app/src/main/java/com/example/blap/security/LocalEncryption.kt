package com.example.blap.security

import android.content.SharedPreferences
import java.io.File
import java.security.SecureRandom
import java.util.Base64

object PreferenceMigration {
    fun copyAndClear(source: SharedPreferences, destination: SharedPreferences) {
        val snapshot = source.all
        if (snapshot.isEmpty()) return
        val editor = destination.edit()
        snapshot.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        check(editor.commit()) { "Failed to copy preferences into encrypted storage" }
        check(source.edit().clear().commit()) { "Failed to clear plaintext preferences" }
    }
}

object SqliteFileHeader {
    private val PLAINTEXT = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isPlaintext(file: File): Boolean {
        if (!file.isFile || file.length() < PLAINTEXT.size) return false
        val header = ByteArray(PLAINTEXT.size)
        file.inputStream().use { stream ->
            if (stream.read(header) != header.size) return false
        }
        return header.contentEquals(PLAINTEXT)
    }
}

object DatabasePassphrase {
    const val PREF_KEY = "sqlcipher_password"

    fun getOrCreate(
        prefs: SharedPreferences,
        random: () -> ByteArray = {
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        },
    ): String {
        val existing = prefs.getString(PREF_KEY, null)
        if (!existing.isNullOrEmpty()) return existing
        val created = Base64.getEncoder().encodeToString(random().also { require(it.size == 32) })
        check(prefs.edit().putString(PREF_KEY, created).commit()) {
            "Failed to persist database passphrase"
        }
        return created
    }
}
