package com.example.blap.security

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

object EncryptedPreferences {
    private val lock = Any()
    private val opened = mutableMapOf<String, SharedPreferences>()
    @Volatile private var masterKey: MasterKey? = null

    fun open(context: Context, name: String): SharedPreferences {
        val app = context.applicationContext
        synchronized(lock) {
            opened[name]?.let { return it }
            val key = masterKey ?: MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                .also { masterKey = it }
            val encrypted = EncryptedSharedPreferences.create(
                app,
                "${name}_encrypted",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            PreferenceMigration.copyAndClear(app.getSharedPreferences(name, Context.MODE_PRIVATE), encrypted)
            opened[name] = encrypted
            return encrypted
        }
    }
}

object EncryptedSqlite {
    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var cachedPassphrase: String? = null

    fun prepareAndPassphrase(context: Context, databaseName: String): String {
        val app = context.applicationContext
        load(app)
        val passphrase = passphrase(app)
        encryptPlaintextIfNeeded(app, databaseName, passphrase)
        return passphrase
    }

    fun passphrase(context: Context): String {
        cachedPassphrase?.let { return it }
        synchronized(lock) {
            cachedPassphrase?.let { return it }
            return DatabasePassphrase.getOrCreate(EncryptedPreferences.open(context, "device_crypto"))
                .also { cachedPassphrase = it }
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }

    private fun encryptPlaintextIfNeeded(context: Context, databaseName: String, passphrase: String) {
        val dbFile = context.getDatabasePath(databaseName)
        if (!SqliteFileHeader.isPlaintext(dbFile)) return
        synchronized(lock) {
            if (!SqliteFileHeader.isPlaintext(dbFile)) return
            val encrypted = File(dbFile.parentFile, "${dbFile.name}.encrypting")
            if (encrypted.exists()) encrypted.delete()
            copyPlaintextToEncrypted(dbFile, encrypted, passphrase)
            replaceDatabaseFile(dbFile, encrypted)
        }
    }

    private fun copyPlaintextToEncrypted(sourceFile: File, destFile: File, passphrase: String) {
        val source = android.database.sqlite.SQLiteDatabase.openDatabase(
            sourceFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            runCatching { source.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close() }
            destFile.parentFile?.mkdirs()
            val dest = SQLiteDatabase.openOrCreateDatabase(destFile, passphrase, null, null)
            try {
                dest.beginTransaction()
                copySchemaAndRows(source, dest)
                dest.version = source.version
                dest.setTransactionSuccessful()
            } finally {
                dest.endTransaction()
                dest.close()
            }
        } finally {
            source.close()
        }
    }

    private fun copySchemaAndRows(
        source: android.database.sqlite.SQLiteDatabase,
        dest: SQLiteDatabase,
    ) {
        source.rawQuery(
            """
            SELECT name, type, sql FROM sqlite_master
            WHERE sql IS NOT NULL
              AND name NOT LIKE 'sqlite_%'
              AND name NOT LIKE 'android_%'
            ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val type = cursor.getString(1)
                dest.execSQL(cursor.getString(2))
                if (type == "table") copyTable(source, dest, name)
            }
        }
        if (tableExists(source, "sqlite_sequence")) copyTable(source, dest, "sqlite_sequence")
    }

    private fun copyTable(
        source: android.database.sqlite.SQLiteDatabase,
        dest: SQLiteDatabase,
        table: String,
    ) {
        source.rawQuery("SELECT * FROM ${quote(table)}", null).use { rows ->
            val count = rows.columnCount
            while (rows.moveToNext()) {
                val values = ContentValues()
                for (index in 0 until count) {
                    val column = rows.getColumnName(index)
                    when (rows.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(column, rows.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(column, rows.getDouble(index))
                        Cursor.FIELD_TYPE_STRING -> values.put(column, rows.getString(index))
                        Cursor.FIELD_TYPE_BLOB -> values.put(column, rows.getBlob(index))
                    }
                }
                dest.insert(table, null, values)
            }
        }
    }

    private fun tableExists(source: android.database.sqlite.SQLiteDatabase, table: String): Boolean =
        source.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    private fun replaceDatabaseFile(original: File, encrypted: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(original.path + suffix).delete()
        }
        check(!original.exists() || original.delete()) {
            "Could not replace plaintext database ${original.name}"
        }
        if (!encrypted.renameTo(original)) {
            encrypted.copyTo(original, overwrite = true)
            encrypted.delete()
        }
    }
}

internal inline fun <T> SQLiteDatabase.inTransaction(body: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = body()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}
