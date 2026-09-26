package com.example.blap.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

interface ChatStore {
    fun savePeer(peerId: String, name: String, phoneHash: String = "")
    fun saveMeshPeer(peerId: String, name: String, phoneHash: String = "")
    fun saveContact(contact: SavedContact)
    fun getSavedContacts(): List<SavedContact>
    fun deleteContact(contactId: String) = Unit
    fun linkContact(phoneHash: String, peerId: String)
    fun getKnownContacts(): List<GroupMember>
    fun saveGroup(group: PrivateGroup)
    fun getGroups(): List<PrivateGroup>
    fun getCloudPendingGroups(): List<PrivateGroup> = emptyList()
    fun markGroupCloudSynced(groupId: String, revision: Long) = Unit
    fun deleteGroup(groupId: String) = Unit
    fun isGroupMember(groupId: String, peerId: String, phoneHash: String = ""): Boolean
    fun saveMessage(message: ChatMessage): Boolean
    fun updateMessageStatus(messageId: String, status: MessageStatus)
    fun getConversations(): List<ConversationSummary>
    fun getMessages(peerId: String): List<ChatMessage>
    fun hasCloudPeerMessage(peerId: String): Boolean = getMessages(peerId).any {
        it.author == MessageAuthor.PEER && it.senderAccountId.isNotBlank()
    }
    fun getPendingMessages(peerId: String): List<ChatMessage>
    fun getCloudPendingMessages(): List<ChatMessage> = emptyList()
    fun markCloudSynced(messageId: String) = Unit
    fun moveConversation(fromPeerId: String, toPeerId: String) = Unit
    fun close()
}

class SqliteChatStore(context: Context, scope: String = "") : SQLiteOpenHelper(
    context,
    "nearby_chat$scope.db",
    null,
    10,
), ChatStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE peers (
                peer_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                phone_hash TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                message_id TEXT PRIMARY KEY,
                peer_id TEXT NOT NULL,
                text TEXT NOT NULL,
                author INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                status INTEGER NOT NULL,
                sender_id TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                sender_phone_hash TEXT NOT NULL DEFAULT '',
                sender_account_id TEXT NOT NULL DEFAULT '',
                cloud_synced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_peer_time ON messages(peer_id, sent_at)")
        createGroupTables(db)
        createMeshPeersTable(db)
        createContactsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_name TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) createGroupTables(db)
        if (oldVersion < 4) createMeshPeersTable(db)
        if (oldVersion < 5) {
            addColumnIfMissing(db, "peers", "phone_hash", "phone_hash TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "mesh_peers", "phone_hash", "phone_hash TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "group_members", "phone_hash", "phone_hash TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(
                db,
                "messages",
                "sender_phone_hash",
                "sender_phone_hash TEXT NOT NULL DEFAULT ''",
            )
            createContactsTable(db)
        }
        if (oldVersion < 6) {
            addColumnIfMissing(db, "app_contacts", "email", "email TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "bio", "bio TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "website_url", "website_url TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "instagram_url", "instagram_url TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "x_url", "x_url TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "linkedin_url", "linkedin_url TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "github_url", "github_url TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "source", "source INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "app_contacts", "updated_at", "updated_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, "messages", "cloud_synced", "cloud_synced INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            addColumnIfMissing(db, "chat_groups", "cloud_synced", "cloud_synced INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 9) {
            db.execSQL(
                """
                CREATE TABLE app_contacts_new (
                    contact_id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    phone_number TEXT NOT NULL,
                    phone_hash TEXT NOT NULL DEFAULT '',
                    linked_peer_id TEXT,
                    email TEXT NOT NULL DEFAULT '',
                    google_account_email TEXT NOT NULL DEFAULT '',
                    cloud_user_id TEXT NOT NULL DEFAULT '',
                    bio TEXT NOT NULL DEFAULT '',
                    website_url TEXT NOT NULL DEFAULT '',
                    instagram_url TEXT NOT NULL DEFAULT '',
                    x_url TEXT NOT NULL DEFAULT '',
                    linkedin_url TEXT NOT NULL DEFAULT '',
                    github_url TEXT NOT NULL DEFAULT '',
                    source INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO app_contacts_new (
                    contact_id, name, phone_number, phone_hash, linked_peer_id, email,
                    bio, website_url, instagram_url, x_url, linkedin_url, github_url, source, updated_at
                ) SELECT contact_id, name, phone_number, phone_hash, linked_peer_id, email,
                    bio, website_url, instagram_url, x_url, linkedin_url, github_url, source, updated_at
                  FROM app_contacts
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE app_contacts")
            db.execSQL("ALTER TABLE app_contacts_new RENAME TO app_contacts")
            db.execSQL("CREATE UNIQUE INDEX app_contacts_phone_hash ON app_contacts(phone_hash) WHERE phone_hash <> ''")
        }
        if (oldVersion < 10) {
            addColumnIfMissing(db, "messages", "sender_account_id", "sender_account_id TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "app_contacts", "cloud_user_id", "cloud_user_id TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "chat_groups", "owner_account_id", "owner_account_id TEXT NOT NULL DEFAULT ''")
        }
    }

    @Synchronized
    override fun savePeer(peerId: String, name: String, phoneHash: String) {
        val values = ContentValues().apply {
            put("peer_id", peerId)
            put("name", name)
            put("phone_hash", phoneHash)
            put("last_seen", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("peers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun saveMeshPeer(peerId: String, name: String, phoneHash: String) {
        val values = ContentValues().apply {
            put("peer_id", peerId)
            put("name", name)
            put("phone_hash", phoneHash)
            put("last_seen", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "mesh_peers",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun saveContact(contact: SavedContact) {
        val values = ContentValues().apply {
            put("contact_id", contact.id)
            put("name", contact.name)
            put("phone_number", contact.phoneNumber)
            put("phone_hash", contact.phoneHash)
            put("linked_peer_id", contact.linkedPeerId)
            put("email", contact.email)
            put("google_account_email", contact.googleAccountEmail)
            put("cloud_user_id", contact.cloudUserId)
            put("bio", contact.bio)
            put("website_url", contact.websiteUrl)
            put("instagram_url", contact.instagramUrl)
            put("x_url", contact.xUrl)
            put("linkedin_url", contact.linkedinUrl)
            put("github_url", contact.githubUrl)
            put("source", contact.source.ordinal)
            put("updated_at", contact.updatedAt)
        }
        writableDatabase.insertWithOnConflict(
            "app_contacts",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun getSavedContacts(): List<SavedContact> {
        val contacts = mutableListOf<SavedContact>()
        readableDatabase.query(
            "app_contacts",
            arrayOf(
                "contact_id", "name", "phone_number", "phone_hash", "linked_peer_id",
                "email", "google_account_email", "cloud_user_id", "bio", "website_url", "instagram_url", "x_url", "linkedin_url",
                "github_url", "source", "updated_at",
            ),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                contacts += SavedContact(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    phoneNumber = cursor.getString(2),
                    phoneHash = cursor.getString(3),
                    linkedPeerId = cursor.getString(4),
                    email = cursor.getString(5),
                    googleAccountEmail = cursor.getString(6),
                    cloudUserId = cursor.getString(7),
                    bio = cursor.getString(8),
                    websiteUrl = cursor.getString(9),
                    instagramUrl = cursor.getString(10),
                    xUrl = cursor.getString(11),
                    linkedinUrl = cursor.getString(12),
                    githubUrl = cursor.getString(13),
                    source = ContactSource.entries[cursor.getInt(14).coerceIn(0, ContactSource.entries.lastIndex)],
                    updatedAt = cursor.getLong(15),
                )
            }
        }
        return contacts
    }

    @Synchronized
    override fun deleteContact(contactId: String) {
        writableDatabase.delete("app_contacts", "contact_id = ?", arrayOf(contactId))
    }

    @Synchronized
    override fun linkContact(phoneHash: String, peerId: String) {
        if (phoneHash.isBlank()) return
        val values = ContentValues().apply { put("linked_peer_id", peerId) }
        writableDatabase.update("app_contacts", values, "phone_hash = ?", arrayOf(phoneHash))
    }

    @Synchronized
    override fun getKnownContacts(): List<GroupMember> {
        val contacts = mutableListOf<GroupMember>()
        val query = """
            SELECT peer_id, name, phone_hash FROM peers WHERE peer_id != ?
            UNION ALL
            SELECT peer_id, name, phone_hash FROM mesh_peers
        """.trimIndent()
        readableDatabase.rawQuery(query, arrayOf(MeshGroup.ID)).use { cursor ->
            while (cursor.moveToNext()) {
                contacts += GroupMember(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
        }
        return contacts.distinctBy(GroupMember::peerId).sortedBy { it.name.lowercase() }
    }

    @Synchronized
    override fun saveGroup(group: PrivateGroup) {
        val storedRevision = readableDatabase.rawQuery(
            "SELECT created_at FROM chat_groups WHERE group_id = ?",
            arrayOf(group.id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else Long.MIN_VALUE }
        if (storedRevision > group.createdAt) return
        writableDatabase.transaction {
            val groupValues = ContentValues().apply {
                put("group_id", group.id)
                put("name", group.name)
                put("owner_id", group.ownerId)
                put("created_at", group.createdAt)
                put("cloud_synced", if (group.cloudSynced) 1 else 0)
                put("owner_account_id", group.ownerAccountId)
            }
            insertWithOnConflict("chat_groups", null, groupValues, SQLiteDatabase.CONFLICT_REPLACE)
            delete("group_members", "group_id = ?", arrayOf(group.id))
            group.members.distinctBy(GroupMember::peerId).forEach { member ->
                val memberValues = ContentValues().apply {
                    put("group_id", group.id)
                    put("peer_id", member.peerId)
                    put("name", member.name)
                    put("phone_hash", member.phoneHash)
                }
                insert("group_members", null, memberValues)
            }
        }
    }

    @Synchronized
    override fun getGroups(): List<PrivateGroup> {
        val groups = mutableListOf<PrivateGroup>()
        readableDatabase.query(
            "chat_groups",
            arrayOf("group_id", "name", "owner_id", "created_at", "cloud_synced", "owner_account_id"),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val groupId = cursor.getString(0)
                groups += PrivateGroup(
                    id = groupId,
                    name = cursor.getString(1),
                    ownerId = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                    members = getGroupMembers(groupId),
                    cloudSynced = cursor.getInt(4) != 0,
                    ownerAccountId = cursor.getString(5),
                )
            }
        }
        return groups
    }

    @Synchronized
    override fun getCloudPendingGroups(): List<PrivateGroup> = getGroups().filterNot(PrivateGroup::cloudSynced)

    @Synchronized
    override fun markGroupCloudSynced(groupId: String, revision: Long) {
        writableDatabase.update(
            "chat_groups", ContentValues().apply { put("cloud_synced", 1) },
            "group_id = ? AND created_at = ?", arrayOf(groupId, revision.toString()),
        )
    }

    @Synchronized
    override fun deleteGroup(groupId: String) {
        writableDatabase.transaction {
            delete("group_members", "group_id = ?", arrayOf(groupId))
            delete("messages", "peer_id = ?", arrayOf(groupId))
            delete("chat_groups", "group_id = ?", arrayOf(groupId))
        }
    }

    @Synchronized
    override fun isGroupMember(groupId: String, peerId: String, phoneHash: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM group_members WHERE group_id = ? AND (peer_id = ? OR (? != '' AND phone_hash = ?)) LIMIT 1",
            arrayOf(groupId, peerId, phoneHash, phoneHash),
        ).use { cursor -> return cursor.moveToFirst() }
    }

    @Synchronized
    override fun saveMessage(message: ChatMessage): Boolean {
        val values = ContentValues().apply {
            put("message_id", message.id)
            put("peer_id", message.peerId)
            put("text", message.text)
            put("author", message.author.ordinal)
            put("sent_at", message.sentAt)
            put("status", message.status.ordinal)
            put("sender_id", message.senderId)
            put("sender_name", message.senderName)
            put("sender_phone_hash", message.senderPhoneHash)
            put("sender_account_id", message.senderAccountId)
            put("cloud_synced", if (message.cloudSynced) 1 else 0)
        }
        val row = writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return row != -1L
    }

    @Synchronized
    override fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val values = ContentValues().apply { put("status", status.ordinal) }
        writableDatabase.update(
            "messages",
            values,
            "message_id = ? AND status < ?",
            arrayOf(messageId, status.ordinal.toString()),
        )
    }

    @Synchronized
    override fun getConversations(): List<ConversationSummary> {
        val conversations = mutableListOf<ConversationSummary>()
        val query = """
            SELECT p.peer_id, p.name,
                COALESCE((SELECT text FROM messages m WHERE m.peer_id = p.peer_id
                    ORDER BY sent_at DESC, rowid DESC LIMIT 1), '') AS last_message,
                COALESCE((SELECT sent_at FROM messages m WHERE m.peer_id = p.peer_id
                    ORDER BY sent_at DESC, rowid DESC LIMIT 1), p.last_seen) AS last_message_at
            FROM peers p
            ORDER BY last_message_at DESC
        """.trimIndent()

        readableDatabase.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                conversations += ConversationSummary(
                    peerId = cursor.getString(0),
                    name = cursor.getString(1),
                    lastMessage = cursor.getString(2),
                    lastMessageAt = cursor.getLong(3),
                    type = if (cursor.getString(0) == MeshGroup.ID) {
                        ConversationType.OPEN_MESH
                    } else {
                        ConversationType.DIRECT
                    },
                )
            }
        }
        getGroups().forEach { group ->
            val lastMessage = getMessages(group.id).lastOrNull()
            conversations += ConversationSummary(
                peerId = group.id,
                name = group.name,
                lastMessage = lastMessage?.text.orEmpty(),
                lastMessageAt = lastMessage?.sentAt ?: group.createdAt,
                type = ConversationType.PRIVATE_GROUP,
                memberCount = group.members.size,
            )
        }
        return conversations.sortedByDescending(ConversationSummary::lastMessageAt)
    }

    @Synchronized
    override fun getMessages(peerId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages",
            MESSAGE_COLUMNS,
            "peer_id = ?",
            arrayOf(peerId),
            null,
            null,
            "sent_at ASC, rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) messages += readMessage(cursor)
        }
        return messages
    }

    @Synchronized
    override fun hasCloudPeerMessage(peerId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM messages WHERE peer_id = ? AND author = ? AND sender_account_id != '' LIMIT 1",
        arrayOf(peerId, MessageAuthor.PEER.ordinal.toString()),
    ).use { it.moveToFirst() }

    @Synchronized
    override fun getPendingMessages(peerId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages",
            MESSAGE_COLUMNS,
            "peer_id = ? AND author = ? AND status < ?",
            arrayOf(peerId, MessageAuthor.ME.ordinal.toString(), MessageStatus.DELIVERED.ordinal.toString()),
            null,
            null,
            "sent_at ASC, rowid ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) messages += readMessage(cursor)
        }
        return messages
    }

    @Synchronized
    override fun getCloudPendingMessages(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages", MESSAGE_COLUMNS,
            "author = ? AND cloud_synced = 0 AND peer_id != ?",
            arrayOf(MessageAuthor.ME.ordinal.toString(), MeshGroup.ID),
            null, null, "sent_at ASC, rowid ASC",
        ).use { cursor -> while (cursor.moveToNext()) messages += readMessage(cursor) }
        return messages
    }

    @Synchronized
    override fun markCloudSynced(messageId: String) {
        writableDatabase.update(
            "messages", ContentValues().apply { put("cloud_synced", 1) },
            "message_id = ?", arrayOf(messageId),
        )
    }

    @Synchronized
    override fun moveConversation(fromPeerId: String, toPeerId: String) {
        if (fromPeerId == toPeerId) return
        writableDatabase.transaction {
            update("messages", ContentValues().apply { put("peer_id", toPeerId) },
                "peer_id = ?", arrayOf(fromPeerId))
            delete("peers", "peer_id = ?", arrayOf(fromPeerId))
        }
    }

    private fun readMessage(cursor: Cursor): ChatMessage {
        val authorIndex = cursor.getInt(3).coerceIn(0, MessageAuthor.entries.lastIndex)
        val statusIndex = cursor.getInt(5).coerceIn(0, MessageStatus.entries.lastIndex)
        return ChatMessage(
            id = cursor.getString(0),
            peerId = cursor.getString(1),
            text = cursor.getString(2),
            author = MessageAuthor.entries[authorIndex],
            sentAt = cursor.getLong(4),
            status = MessageStatus.entries[statusIndex],
            senderId = cursor.getString(6),
            senderName = cursor.getString(7),
            senderPhoneHash = cursor.getString(8),
            cloudSynced = cursor.getInt(9) != 0,
            senderAccountId = cursor.getString(10),
        )
    }

    private fun getGroupMembers(groupId: String): List<GroupMember> {
        val members = mutableListOf<GroupMember>()
        readableDatabase.query(
            "group_members",
            arrayOf("peer_id", "name", "phone_hash"),
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                members += GroupMember(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
        }
        return members
    }

    private fun createGroupTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_groups (
                group_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                owner_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                cloud_synced INTEGER NOT NULL DEFAULT 0,
                owner_account_id TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                peer_id TEXT NOT NULL,
                name TEXT NOT NULL,
                phone_hash TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (group_id, peer_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS group_members_peer ON group_members(peer_id)")
    }

    private fun createMeshPeersTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mesh_peers (
                peer_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                phone_hash TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createContactsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_contacts (
                contact_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                phone_number TEXT NOT NULL,
                phone_hash TEXT NOT NULL DEFAULT '',
                linked_peer_id TEXT,
                email TEXT NOT NULL DEFAULT '',
                google_account_email TEXT NOT NULL DEFAULT '',
                cloud_user_id TEXT NOT NULL DEFAULT '',
                bio TEXT NOT NULL DEFAULT '',
                website_url TEXT NOT NULL DEFAULT '',
                instagram_url TEXT NOT NULL DEFAULT '',
                x_url TEXT NOT NULL DEFAULT '',
                linkedin_url TEXT NOT NULL DEFAULT '',
                github_url TEXT NOT NULL DEFAULT '',
                source INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS app_contacts_phone_hash ON app_contacts(phone_hash) WHERE phone_hash <> ''")
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $definition")
    }

    private companion object {
        val MESSAGE_COLUMNS = arrayOf(
            "message_id",
            "peer_id",
            "text",
            "author",
            "sent_at",
            "status",
            "sender_id",
            "sender_name",
            "sender_phone_hash",
            "cloud_synced",
            "sender_account_id",
        )
    }
}
