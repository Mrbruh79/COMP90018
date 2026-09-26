package com.example.blap.event

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

interface EventStore {
    fun saveEvent(event: CommunityEvent)
    fun getEvent(eventId: String): CommunityEvent?
    fun getEvents(): List<CommunityEvent>
    fun saveMembership(membership: EventMembership)
    fun getMembership(eventId: String, userId: String): EventMembership?
    fun saveAnnouncement(announcement: EventAnnouncement): Boolean
    fun markAnnouncementSynced(announcementId: String)
    fun getAnnouncements(eventId: String, limit: Int = 100): List<EventAnnouncement>
    fun getPendingAnnouncements(eventId: String): List<EventAnnouncement>
    fun saveChatMessage(message: EventChatMessage): Boolean
    fun getChatMessages(eventId: String, limit: Int = 5_000): List<EventChatMessage>
    fun getRecentChatMessages(eventId: String, limit: Int = 50): List<EventChatMessage>
    fun deleteLocalEventData(eventId: String, userId: String)
    fun purgeEvent(eventId: String)
    fun close()
}

class InMemoryEventStore : EventStore {
    private val events = linkedMapOf<String, CommunityEvent>()
    private val memberships = linkedMapOf<Pair<String, String>, EventMembership>()
    private val announcements = linkedMapOf<String, EventAnnouncement>()
    private val messages = linkedMapOf<String, EventChatMessage>()

    override fun saveEvent(event: CommunityEvent) {
        if ((events[event.id]?.updatedAt ?: Long.MIN_VALUE) > event.updatedAt) return
        events[event.id] = event
    }

    override fun getEvent(eventId: String): CommunityEvent? = events[eventId]

    override fun getEvents(): List<CommunityEvent> = events.values.sortedBy(CommunityEvent::startsAt)

    override fun saveMembership(membership: EventMembership) {
        memberships[membership.eventId to membership.userId] = membership
    }

    override fun getMembership(eventId: String, userId: String): EventMembership? =
        memberships[eventId to userId]

    override fun saveAnnouncement(announcement: EventAnnouncement): Boolean {
        val existing = announcements[announcement.id]
        if (existing != null && existing.revision >= announcement.revision) return false
        announcements[announcement.id] = announcement
        return true
    }

    override fun markAnnouncementSynced(announcementId: String) {
        announcements[announcementId]?.let { announcements[announcementId] = it.copy(syncedToCloud = true) }
    }

    override fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> = announcements.values
        .filter { it.eventId == eventId }
        .sortedBy(EventAnnouncement::createdAt)
        .takeLast(limit.coerceIn(1, 500))

    override fun getPendingAnnouncements(eventId: String): List<EventAnnouncement> = announcements.values
        .filter { it.eventId == eventId && !it.syncedToCloud }
        .sortedBy(EventAnnouncement::createdAt)

    override fun saveChatMessage(message: EventChatMessage): Boolean {
        if (message.id in messages) return false
        messages[message.id] = message
        return true
    }

    override fun getRecentChatMessages(eventId: String, limit: Int): List<EventChatMessage> = messages.values
        .filter { it.eventId == eventId }
        .sortedBy(EventChatMessage::createdAt)
        .takeLast(limit.coerceIn(1, 50))

    override fun getChatMessages(eventId: String, limit: Int): List<EventChatMessage> = messages.values
        .filter { it.eventId == eventId }
        .sortedBy(EventChatMessage::createdAt)
        .takeLast(limit.coerceIn(1, 5_000))

    override fun deleteLocalEventData(eventId: String, userId: String) {
        events.remove(eventId)
        memberships.remove(eventId to userId)
        announcements.entries.removeAll { it.value.eventId == eventId }
        messages.entries.removeAll { it.value.eventId == eventId }
    }

    override fun purgeEvent(eventId: String) {
        events.remove(eventId)
        memberships.entries.removeAll { it.value.eventId == eventId }
        announcements.entries.removeAll { it.value.eventId == eventId }
        messages.entries.removeAll { it.value.eventId == eventId }
    }

    override fun close() = Unit
}

class SqliteEventStore(context: Context, scope: String = "") : SQLiteOpenHelper(
    context,
    "community_events$scope.db",
    null,
    5,
), EventStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                event_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                venue_name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                radius_metres REAL NOT NULL,
                starts_at INTEGER NOT NULL,
                ends_at INTEGER NOT NULL,
                created_by TEXT NOT NULL,
                admin_ids TEXT NOT NULL,
                member_ids TEXT NOT NULL,
                admin_public_keys TEXT NOT NULL,
                visibility INTEGER NOT NULL,
                requires_sign_in INTEGER NOT NULL,
                private_mesh_secret TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE event_memberships (
                event_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                role INTEGER NOT NULL,
                joined_at INTEGER NOT NULL,
                blocked_at INTEGER,
                left_at INTEGER,
                access_method INTEGER,
                checked_in_at INTEGER,
                PRIMARY KEY (event_id, user_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE event_announcements (
                announcement_id TEXT PRIMARY KEY,
                event_id TEXT NOT NULL,
                admin_id TEXT NOT NULL,
                admin_name TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                signature TEXT NOT NULL,
                synced_to_cloud INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE event_chat_messages (
                message_id TEXT PRIMARY KEY,
                event_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX event_time ON events(starts_at, ends_at)")
        db.execSQL("CREATE INDEX announcement_event_time ON event_announcements(event_id, created_at)")
        db.execSQL("CREATE INDEX event_chat_event_time ON event_chat_messages(event_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN venue_name TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE events ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE events SET updated_at = created_at WHERE updated_at = 0")
            db.execSQL("ALTER TABLE events ADD COLUMN deleted_at INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE events ADD COLUMN member_ids TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE events SET member_ids = admin_ids WHERE member_ids = ''")
            db.execSQL("ALTER TABLE events ADD COLUMN visibility INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE events ADD COLUMN private_mesh_secret TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE events ADD COLUMN requires_sign_in INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized
    override fun saveEvent(event: CommunityEvent) {
        val existingUpdatedAt = readableDatabase.rawQuery(
            "SELECT updated_at FROM events WHERE event_id = ?",
            arrayOf(event.id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else Long.MIN_VALUE }
        if (existingUpdatedAt > event.updatedAt) return
        val values = ContentValues().apply {
            put("event_id", event.id)
            put("title", event.title)
            put("description", event.description)
            put("venue_name", event.venueName)
            put("latitude", event.latitude)
            put("longitude", event.longitude)
            put("radius_metres", event.radiusMetres)
            put("starts_at", event.startsAt)
            put("ends_at", event.endsAt)
            put("created_by", event.createdBy)
            put("admin_ids", encodeSet(event.adminIds))
            put("member_ids", encodeSet(event.memberIds))
            put("admin_public_keys", encodeMap(event.adminPublicKeys))
            put("visibility", event.visibility.ordinal)
            put("requires_sign_in", if (event.requiresSignIn) 1 else 0)
            put("private_mesh_secret", event.privateMeshSecret)
            put("created_at", event.createdAt)
            put("updated_at", event.updatedAt)
            putNullableLong("deleted_at", event.deletedAt)
        }
        writableDatabase.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun getEvent(eventId: String): CommunityEvent? = readableDatabase.query(
        "events",
        EVENT_COLUMNS,
        "event_id = ?",
        arrayOf(eventId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) readEvent(cursor) else null }

    @Synchronized
    override fun getEvents(): List<CommunityEvent> {
        val events = mutableListOf<CommunityEvent>()
        readableDatabase.query(
            "events",
            EVENT_COLUMNS,
            null,
            null,
            null,
            null,
            "starts_at ASC",
        ).use { cursor -> while (cursor.moveToNext()) events += readEvent(cursor) }
        return events
    }

    @Synchronized
    override fun saveMembership(membership: EventMembership) {
        val values = ContentValues().apply {
            put("event_id", membership.eventId)
            put("user_id", membership.userId)
            put("display_name", membership.displayName)
            put("role", membership.role.ordinal)
            put("joined_at", membership.joinedAt)
            putNullableLong("blocked_at", membership.blockedAt)
            putNullableLong("left_at", membership.leftAt)
            if (membership.accessMethod == null) putNull("access_method")
            else put("access_method", membership.accessMethod.ordinal)
            putNullableLong("checked_in_at", membership.checkedInAt)
        }
        writableDatabase.insertWithOnConflict(
            "event_memberships",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    override fun getMembership(eventId: String, userId: String): EventMembership? =
        readableDatabase.query(
            "event_memberships",
            MEMBERSHIP_COLUMNS,
            "event_id = ? AND user_id = ?",
            arrayOf(eventId, userId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            EventMembership(
                eventId = cursor.getString(0),
                userId = cursor.getString(1),
                displayName = cursor.getString(2),
                role = EventRole.entries[cursor.getInt(3).coerceIn(0, EventRole.entries.lastIndex)],
                joinedAt = cursor.getLong(4),
                blockedAt = cursor.nullableLong(5),
                leftAt = cursor.nullableLong(6),
                accessMethod = cursor.nullableInt(7)?.let {
                    EventAccessMethod.entries[it.coerceIn(0, EventAccessMethod.entries.lastIndex)]
                },
                checkedInAt = cursor.nullableLong(8),
            )
        }

    @Synchronized
    override fun saveAnnouncement(announcement: EventAnnouncement): Boolean {
        val existingRevision = readableDatabase.rawQuery(
            "SELECT revision FROM event_announcements WHERE announcement_id = ?",
            arrayOf(announcement.id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else Long.MIN_VALUE }
        if (existingRevision >= announcement.revision) return false
        val values = ContentValues().apply {
            put("announcement_id", announcement.id)
            put("event_id", announcement.eventId)
            put("admin_id", announcement.adminId)
            put("admin_name", announcement.adminName)
            put("text", announcement.text)
            put("created_at", announcement.createdAt)
            put("revision", announcement.revision)
            put("signature", announcement.signature)
            put("synced_to_cloud", if (announcement.syncedToCloud) 1 else 0)
        }
        return writableDatabase.insertWithOnConflict(
            "event_announcements",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ) != -1L
    }

    @Synchronized
    override fun markAnnouncementSynced(announcementId: String) {
        writableDatabase.update(
            "event_announcements",
            ContentValues().apply { put("synced_to_cloud", 1) },
            "announcement_id = ?",
            arrayOf(announcementId),
        )
    }

    @Synchronized
    override fun getAnnouncements(eventId: String, limit: Int): List<EventAnnouncement> {
        val announcements = mutableListOf<EventAnnouncement>()
        readableDatabase.query(
            "event_announcements",
            ANNOUNCEMENT_COLUMNS,
            "event_id = ?",
            arrayOf(eventId),
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor -> while (cursor.moveToNext()) announcements += readAnnouncement(cursor) }
        return announcements.asReversed()
    }

    @Synchronized
    override fun getPendingAnnouncements(eventId: String): List<EventAnnouncement> {
        val announcements = mutableListOf<EventAnnouncement>()
        readableDatabase.query(
            "event_announcements",
            ANNOUNCEMENT_COLUMNS,
            "event_id = ? AND synced_to_cloud = 0",
            arrayOf(eventId),
            null,
            null,
            "created_at ASC",
        ).use { cursor -> while (cursor.moveToNext()) announcements += readAnnouncement(cursor) }
        return announcements
    }

    @Synchronized
    override fun saveChatMessage(message: EventChatMessage): Boolean {
        val values = ContentValues().apply {
            put("message_id", message.id)
            put("event_id", message.eventId)
            put("sender_id", message.senderId)
            put("sender_name", message.senderName)
            put("text", message.text)
            put("created_at", message.createdAt)
        }
        return writableDatabase.insertWithOnConflict(
            "event_chat_messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    @Synchronized
    override fun getRecentChatMessages(eventId: String, limit: Int): List<EventChatMessage> {
        return getChatMessages(eventId, limit.coerceIn(1, MAX_MESH_HISTORY))
    }

    @Synchronized
    override fun getChatMessages(eventId: String, limit: Int): List<EventChatMessage> {
        val messages = mutableListOf<EventChatMessage>()
        readableDatabase.query(
            "event_chat_messages",
            CHAT_COLUMNS,
            "event_id = ?",
            arrayOf(eventId),
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, MAX_LOCAL_HISTORY).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += EventChatMessage(
                    id = cursor.getString(0),
                    eventId = cursor.getString(1),
                    senderId = cursor.getString(2),
                    senderName = cursor.getString(3),
                    text = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return messages.asReversed()
    }

    @Synchronized
    override fun deleteLocalEventData(eventId: String, userId: String) {
        writableDatabase.transaction {
            delete("event_chat_messages", "event_id = ?", arrayOf(eventId))
            delete("event_announcements", "event_id = ?", arrayOf(eventId))
            delete(
                "event_memberships",
                "event_id = ? AND user_id = ?",
                arrayOf(eventId, userId),
            )
            delete("events", "event_id = ?", arrayOf(eventId))
        }
    }

    @Synchronized
    override fun purgeEvent(eventId: String) {
        writableDatabase.transaction {
            delete("event_chat_messages", "event_id = ?", arrayOf(eventId))
            delete("event_announcements", "event_id = ?", arrayOf(eventId))
            delete("event_memberships", "event_id = ?", arrayOf(eventId))
            delete("events", "event_id = ?", arrayOf(eventId))
        }
    }

    private fun readEvent(cursor: android.database.Cursor) = CommunityEvent(
        id = cursor.getString(0),
        title = cursor.getString(1),
        description = cursor.getString(2),
        venueName = cursor.getString(3),
        latitude = cursor.getDouble(4),
        longitude = cursor.getDouble(5),
        radiusMetres = cursor.getDouble(6),
        startsAt = cursor.getLong(7),
        endsAt = cursor.getLong(8),
        createdBy = cursor.getString(9),
        adminIds = decodeSet(cursor.getString(10)),
        memberIds = decodeSet(cursor.getString(11)),
        adminPublicKeys = decodeMap(cursor.getString(12)),
        visibility = EventVisibility.entries[cursor.getInt(13)],
        requiresSignIn = cursor.getInt(14) != 0,
        privateMeshSecret = cursor.getString(15),
        createdAt = cursor.getLong(16),
        updatedAt = cursor.getLong(17),
        deletedAt = cursor.nullableLong(18),
    )

    private fun readAnnouncement(cursor: android.database.Cursor) = EventAnnouncement(
        id = cursor.getString(0),
        eventId = cursor.getString(1),
        adminId = cursor.getString(2),
        adminName = cursor.getString(3),
        text = cursor.getString(4),
        createdAt = cursor.getLong(5),
        revision = cursor.getLong(6),
        signature = cursor.getString(7),
        syncedToCloud = cursor.getInt(8) != 0,
    )

    private fun encodeSet(values: Set<String>) = values.sorted().joinToString(UNIT_SEPARATOR)

    private fun decodeSet(value: String): Set<String> = value.split(UNIT_SEPARATOR)
        .filter(String::isNotBlank)
        .toSet()

    private fun encodeMap(values: Map<String, String>) = values.entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString(RECORD_SEPARATOR) { (key, value) -> "$key$UNIT_SEPARATOR$value" }

    private fun decodeMap(value: String): Map<String, String> = value.split(RECORD_SEPARATOR)
        .mapNotNull { record ->
            val separator = record.indexOf(UNIT_SEPARATOR)
            if (separator <= 0) null else record.substring(0, separator) to record.substring(separator + 1)
        }
        .toMap()

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.nullableInt(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private companion object {
        const val UNIT_SEPARATOR = "\u001F"
        const val RECORD_SEPARATOR = "\u001E"
        const val MAX_MESH_HISTORY = 50
        const val MAX_LOCAL_HISTORY = 5_000

        val EVENT_COLUMNS = arrayOf(
            "event_id", "title", "description", "venue_name", "latitude", "longitude", "radius_metres",
            "starts_at", "ends_at", "created_by", "admin_ids", "member_ids", "admin_public_keys",
            "visibility", "requires_sign_in", "private_mesh_secret", "created_at", "updated_at", "deleted_at",
        )
        val MEMBERSHIP_COLUMNS = arrayOf(
            "event_id", "user_id", "display_name", "role", "joined_at", "blocked_at", "left_at",
            "access_method", "checked_in_at",
        )
        val ANNOUNCEMENT_COLUMNS = arrayOf(
            "announcement_id", "event_id", "admin_id", "admin_name", "text", "created_at",
            "revision", "signature", "synced_to_cloud",
        )
        val CHAT_COLUMNS = arrayOf(
            "message_id", "event_id", "sender_id", "sender_name", "text", "created_at",
        )
    }
}
