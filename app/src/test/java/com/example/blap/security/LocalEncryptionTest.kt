package com.example.blap.security

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEncryptionTest {
    @Test
    fun preferenceMigrationCopiesSupportedTypesAndClearsTheSource() {
        val source = InMemorySharedPreferences()
        val destination = InMemorySharedPreferences()
        source.edit()
            .putString("name", "Ada")
            .putBoolean("discoverable", true)
            .putInt("count", 3)
            .putLong("updated", 99L)
            .putFloat("score", 1.5f)
            .putStringSet("tags", mutableSetOf("a", "b"))
            .commit()

        PreferenceMigration.copyAndClear(source, destination)

        assertEquals("Ada", destination.getString("name", null))
        assertTrue(destination.getBoolean("discoverable", false))
        assertEquals(3, destination.getInt("count", 0))
        assertEquals(99L, destination.getLong("updated", 0L))
        assertEquals(1.5f, destination.getFloat("score", 0f))
        assertEquals(setOf("a", "b"), destination.getStringSet("tags", null))
        assertTrue(source.all.isEmpty())
    }

    @Test
    fun preferenceMigrationLeavesTheDestinationAloneWhenTheSourceIsEmpty() {
        val source = InMemorySharedPreferences()
        val destination = InMemorySharedPreferences()
        destination.edit().putString("keep", "yes").commit()

        PreferenceMigration.copyAndClear(source, destination)

        assertEquals("yes", destination.getString("keep", null))
    }

    @Test
    fun sqliteHeaderDetectsAPlaintextDatabaseAndRejectsEncryptedBytes() {
        val plaintext = File.createTempFile("plain", ".db")
        val encrypted = File.createTempFile("cipher", ".db")
        plaintext.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(16))
        encrypted.writeBytes(ByteArray(32) { 0xA5.toByte() })

        assertTrue(SqliteFileHeader.isPlaintext(plaintext))
        assertFalse(SqliteFileHeader.isPlaintext(encrypted))
        assertFalse(SqliteFileHeader.isPlaintext(File(plaintext.parentFile, "missing.db")))
    }

    @Test
    fun databasePassphraseIsCreatedOnceAndThenReused() {
        val prefs = InMemorySharedPreferences()
        val first = DatabasePassphrase.getOrCreate(prefs) { ByteArray(32) { 7 } }
        val second = DatabasePassphrase.getOrCreate(prefs) { ByteArray(32) { 9 } }

        assertEquals(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), first)
        assertEquals(first, second)
        assertArrayEquals(ByteArray(32) { 7 }, Base64.getDecoder().decode(first))
    }
}
