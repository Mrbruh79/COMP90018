package com.example.blap.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import net.sqlcipher.database.SQLiteDatabase
import org.junit.Before

@RunWith(AndroidJUnit4::class)
class SqliteChatStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<Pair<String, SqliteChatStore>>()
    private val dummyPassphrase = "test_passphrase".toByteArray()

    @Before
    fun setup() {
        SQLiteDatabase.loadLibs(context)
    }

    private fun store(): SqliteChatStore {
        val scope = "_test_${UUID.randomUUID()}"
        return SqliteChatStore(context, dummyPassphrase, scope).also { stores += scope to it }
    }

    private fun message(id: String, peer: String, time: Long = 1L) =
        ChatMessage(id, peer, id, MessageAuthor.PEER, time, MessageStatus.DELIVERED, cloudSynced = true)

    @After
    fun closeTestDatabases() {
        stores.forEach { (scope, store) ->
            store.close()
            context.deleteDatabase("nearby_chat$scope.db")
        }
    }

    @Test
    fun deletionPersistsAndCloudReplayCannotRestoreTheChat() {
        val store = store()
        store.savePeer("account:bob", "Bob")
        store.saveMessage(message("old", "account:bob"))
        store.saveContact(SavedContact("bob", "Bob", "", "", cloudUserId = "bob", username = "bob_user"))
        store.deleteConversation("account:bob")
        store.close()
        val reopened = SqliteChatStore(context, dummyPassphrase, stores.single().first)
        stores[0] = stores.single().first to reopened
        reopened.savePeer("account:bob", "Bob")
        assertFalse(reopened.saveMessage(message("old", "account:bob")))
        assertFalse(reopened.saveMessage(message("uncached-old", "account:bob")))
        assertTrue(reopened.getConversations().isEmpty())
        assertTrue(reopened.getMessages("account:bob").isEmpty())
        assertEquals(1, reopened.getSavedContacts().size)
        assertTrue(reopened.saveMessage(message("new", "account:bob", System.currentTimeMillis() + 1_000)))
        assertEquals("new", reopened.getMessages("account:bob").single().id)
        assertEquals(1, reopened.getConversations().size)
    }

    @Test
    fun reopeningChatDoesNotRestoreDeletedHistory() {
        val store = store()
        store.savePeer("account:bob", "Bob")
        store.saveMessage(message("old", "account:bob", Long.MAX_VALUE))
        store.deleteConversation("account:bob")
        store.reopenConversation("account:bob")
        assertEquals(1, store.getConversations().size)
        assertTrue(store.getMessages("account:bob").isEmpty())
        assertFalse(store.saveMessage(message("old", "account:bob", Long.MAX_VALUE)))
    }

    @Test
    fun aliasMergingMovesMessagesAndRemovesTheDuplicateConversation() {
        val store = store()
        store.savePeer("email:legacy", "Bob")
        store.savePeer("account:bob", "Bob")
        store.saveMessage(message("email-message", "email:legacy"))
        store.saveMessage(message("account-message", "account:bob", 2L))
        store.moveConversation("email:legacy", "account:bob")
        assertEquals(listOf("account:bob"), store.getConversations().map { it.peerId })
        assertEquals(listOf("email-message", "account-message"), store.getMessages("account:bob").map { it.id })
    }

    @Test
    fun mergingAnAliasCannotResurrectDeletedAccountHistory() {
        val store = store()
        store.savePeer("account:bob", "Bob")
        store.deleteConversation("account:bob")
        store.savePeer("email:legacy", "Bob")
        store.saveMessage(message("old", "email:legacy"))
        store.moveConversation("email:legacy", "account:bob")
        assertTrue(store.getMessages("account:bob").isEmpty())
        assertTrue(store.getConversations().isEmpty())
    }

    @Test
    fun versionElevenUpgradeKeepsExistingMessages() {
        val original = store()
        original.savePeer("bob", "Bob")
        original.saveMessage(message("old", "bob"))
        original.sqlCipherDatabase.execSQL("DROP TABLE chat_deletions")
        original.sqlCipherDatabase.execSQL("DROP TABLE deleted_message_ids")
        original.sqlCipherDatabase.version = 11
        original.close()
        val upgraded = SqliteChatStore(context, dummyPassphrase, stores.single().first)
        stores[0] = stores.single().first to upgraded
        assertEquals("old", upgraded.getMessages("bob").single().id)
        upgraded.deleteConversation("bob")
        assertTrue(upgraded.getConversations().isEmpty())
    }

    @Test
    fun sharedPhoneNumberDoesNotOverwriteAnotherAccountOrPairing() {
        val store = store()
        store.saveContact(SavedContact("bob", "Bob", "+12025550198", "shared", "bob-peer", cloudUserId = "bob", username = "bob_user"))
        store.saveContact(SavedContact("sam", "Sam", "+12025550198", "shared", "sam-peer", cloudUserId = "sam", username = "sam_user"))
        store.linkContact("shared", "sam-peer")
        assertEquals(2, store.getSavedContacts().size)
        assertEquals("bob-peer", store.getSavedContacts().single { it.cloudUserId == "bob" }.linkedPeerId)
        assertEquals("sam-peer", store.getSavedContacts().single { it.cloudUserId == "sam" }.linkedPeerId)
    }

    @Test
    fun usernameSurvivesPeerUpdatesAndDatabaseReopen() {
        val original = store()
        original.savePeer("account:bob", "Bob")
        original.savePeerUsername("account:bob", "bob_user")
        original.savePeer("account:bob", "New display name")
        original.close()
        val reopened = SqliteChatStore(context, dummyPassphrase, stores.single().first)
        stores[0] = stores.single().first to reopened

        assertEquals("bob_user", reopened.getConversations().single().username)
        assertEquals("New display name", reopened.getConversations().single().name)
    }

    @Test
    fun aliasMergingPreservesUsernameWhenCanonicalChatHasNone() {
        val store = store()
        store.savePeer("bob-peer", "Bob")
        store.savePeerUsername("bob-peer", "bob_user")
        store.savePeer("account:bob", "Bob")
        store.moveConversation("bob-peer", "account:bob")

        assertEquals("bob_user", store.getConversations().single().username)
    }

    @Test
    fun versionTwelveUpgradeAddsUsernameWithoutLosingChats() {
        val original = store()
        original.savePeer("bob", "Bob")
        original.saveMessage(message("old", "bob"))
        original.sqlCipherDatabase.execSQL("ALTER TABLE peers RENAME TO peers_with_username")
        original.sqlCipherDatabase.execSQL("CREATE TABLE peers(peer_id TEXT PRIMARY KEY, name TEXT NOT NULL, " +
            "phone_hash TEXT NOT NULL DEFAULT '', last_seen INTEGER NOT NULL)")
        original.sqlCipherDatabase.execSQL("INSERT INTO peers SELECT peer_id, name, phone_hash, last_seen FROM peers_with_username")
        original.sqlCipherDatabase.execSQL("DROP TABLE peers_with_username")
        original.sqlCipherDatabase.version = 12
        original.close()
        val upgraded = SqliteChatStore(context, dummyPassphrase, stores.single().first)
        stores[0] = stores.single().first to upgraded

        assertEquals("old", upgraded.getMessages("bob").single().id)
        assertEquals("", upgraded.getConversations().single().username)
        upgraded.savePeerUsername("bob", "bob_user")
        assertEquals("bob_user", upgraded.getConversations().single().username)
    }
}
