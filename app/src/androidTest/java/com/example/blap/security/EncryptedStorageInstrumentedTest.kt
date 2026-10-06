package com.example.blap.security

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.blap.chat.LocalIdentityStore
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun identitySurvivesReopenThroughEncryptedPreferences() {
        val scope = "_enc_${UUID.randomUUID()}"
        val store = LocalIdentityStore(context, scope)
        store.savePhoneNumber("+12025550198")
        store.saveDisplayName("Ada")

        val reopened = LocalIdentityStore(context, scope)
        assertEquals("+12025550198", reopened.getPhoneNumber())
        assertEquals("Ada", reopened.getDisplayName())
        assertTrue(context.getSharedPreferences("chat_identity$scope", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun plaintextSqliteIsEncryptedInPlaceAndRemainsReadable() {
        val name = "migrate_${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        AndroidSQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE notes (body TEXT)")
            db.execSQL("INSERT INTO notes VALUES ('hello')")
            db.version = 4
        }
        assertTrue(SqliteFileHeader.isPlaintext(file))

        val passphrase = EncryptedSqlite.prepareAndPassphrase(context, name)
        assertFalse(SqliteFileHeader.isPlaintext(file))

        SQLiteDatabase.openDatabase(file.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
            db.rawQuery("SELECT body FROM notes", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("hello", cursor.getString(0))
            }
            assertEquals(4, db.version)
        }
        context.deleteDatabase(name)
    }
}
