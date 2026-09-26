package com.example.blap.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

sealed interface NearbyPacket {
    data class Hello(val peerId: String, val name: String, val phoneHash: String) : NearbyPacket

    data class Message(
        val messageId: String,
        val senderId: String,
        val senderName: String,
        val senderPhoneHash: String,
        val recipientId: String,
        val sentAt: Long,
        val text: String,
        val hopsRemaining: Int,
        val isGroup: Boolean,
    ) : NearbyPacket

    data class Acknowledgement(
        val messageId: String,
        val senderId: String,
        val recipientId: String,
        val conversationId: String,
        val hopsRemaining: Int,
        val isGroup: Boolean,
    ) : NearbyPacket

    data class GroupDefinition(
        val groupId: String,
        val name: String,
        val ownerId: String,
        val createdAt: Long,
        val members: List<GroupMember>,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class PeerAnnouncement(
        val peerId: String,
        val name: String,
        val phoneHash: String,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class EventPresence(
        val eventId: String,
        val peerId: String,
        val name: String,
        val userId: String,
        val active: Boolean,
        val accessGranted: Boolean,
    ) : NearbyPacket

    data class EventChatMessage(
        val messageId: String,
        val eventId: String,
        val senderId: String,
        val senderName: String,
        val text: String,
        val sentAt: Long,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class EventAnnouncement(
        val announcementId: String,
        val eventId: String,
        val adminId: String,
        val adminName: String,
        val text: String,
        val createdAt: Long,
        val revision: Long,
        val signature: String,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class EventMutation(
        val eventId: String,
        val adminId: String,
        val title: String,
        val description: String,
        val venueName: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMetres: Double,
        val startsAt: Long,
        val endsAt: Long,
        val createdBy: String,
        val adminIds: List<String>,
        val adminPublicKeys: Map<String, String>,
        val visibility: String,
        val requiresSignIn: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val deletedAt: Long?,
        val signature: String,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class EventAccessRequest(
        val requestId: String,
        val eventId: String,
        val userId: String,
        val peerId: String,
        val displayName: String,
        val requestedAt: Long,
        val hopsRemaining: Int,
    ) : NearbyPacket

    data class EventAccessGrant(
        val grantId: String,
        val requestId: String,
        val eventId: String,
        val userId: String,
        val peerId: String,
        val adminId: String,
        val issuedAt: Long,
        val expiresAt: Long,
        val signature: String,
        val hopsRemaining: Int,
    ) : NearbyPacket
}

object NearbyProtocol {
    private const val MAGIC = 0x424C4150
    private const val VERSION = 8
    private const val HELLO = 1
    private const val MESSAGE = 2
    private const val ACKNOWLEDGEMENT = 3
    private const val GROUP_DEFINITION = 4
    private const val PEER_ANNOUNCEMENT = 5
    private const val EVENT_PRESENCE = 6
    private const val EVENT_CHAT_MESSAGE = 7
    private const val EVENT_ANNOUNCEMENT = 8
    private const val EVENT_MUTATION = 9
    private const val EVENT_ACCESS_REQUEST = 10
    private const val EVENT_ACCESS_GRANT = 11

    fun encode(packet: NearbyPacket): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            when (packet) {
                is NearbyPacket.Hello -> {
                    output.writeInt(HELLO)
                    output.writeUTF(packet.peerId)
                    output.writeUTF(packet.name)
                    output.writeUTF(packet.phoneHash)
                }

                is NearbyPacket.Message -> {
                    output.writeInt(MESSAGE)
                    output.writeUTF(packet.messageId)
                    output.writeUTF(packet.senderId)
                    output.writeUTF(packet.senderName)
                    output.writeUTF(packet.senderPhoneHash)
                    output.writeUTF(packet.recipientId)
                    output.writeLong(packet.sentAt)
                    output.writeUTF(packet.text)
                    output.writeInt(packet.hopsRemaining)
                    output.writeBoolean(packet.isGroup)
                }

                is NearbyPacket.Acknowledgement -> {
                    output.writeInt(ACKNOWLEDGEMENT)
                    output.writeUTF(packet.messageId)
                    output.writeUTF(packet.senderId)
                    output.writeUTF(packet.recipientId)
                    output.writeUTF(packet.conversationId)
                    output.writeInt(packet.hopsRemaining)
                    output.writeBoolean(packet.isGroup)
                }

                is NearbyPacket.GroupDefinition -> {
                    output.writeInt(GROUP_DEFINITION)
                    output.writeUTF(packet.groupId)
                    output.writeUTF(packet.name)
                    output.writeUTF(packet.ownerId)
                    output.writeLong(packet.createdAt)
                    output.writeInt(packet.members.size)
                    packet.members.forEach { member ->
                        output.writeUTF(member.peerId)
                        output.writeUTF(member.name)
                        output.writeUTF(member.phoneHash)
                    }
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.PeerAnnouncement -> {
                    output.writeInt(PEER_ANNOUNCEMENT)
                    output.writeUTF(packet.peerId)
                    output.writeUTF(packet.name)
                    output.writeUTF(packet.phoneHash)
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.EventPresence -> {
                    output.writeInt(EVENT_PRESENCE)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.peerId)
                    output.writeUTF(packet.name)
                    output.writeUTF(packet.userId)
                    output.writeBoolean(packet.active)
                    output.writeBoolean(packet.accessGranted)
                }

                is NearbyPacket.EventChatMessage -> {
                    output.writeInt(EVENT_CHAT_MESSAGE)
                    output.writeUTF(packet.messageId)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.senderId)
                    output.writeUTF(packet.senderName)
                    output.writeUTF(packet.text)
                    output.writeLong(packet.sentAt)
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.EventAnnouncement -> {
                    output.writeInt(EVENT_ANNOUNCEMENT)
                    output.writeUTF(packet.announcementId)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.adminId)
                    output.writeUTF(packet.adminName)
                    output.writeUTF(packet.text)
                    output.writeLong(packet.createdAt)
                    output.writeLong(packet.revision)
                    output.writeUTF(packet.signature)
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.EventMutation -> {
                    output.writeInt(EVENT_MUTATION)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.adminId)
                    output.writeUTF(packet.title)
                    output.writeUTF(packet.description)
                    output.writeUTF(packet.venueName)
                    output.writeDouble(packet.latitude)
                    output.writeDouble(packet.longitude)
                    output.writeDouble(packet.radiusMetres)
                    output.writeLong(packet.startsAt)
                    output.writeLong(packet.endsAt)
                    output.writeUTF(packet.createdBy)
                    output.writeInt(packet.adminIds.size)
                    packet.adminIds.forEach(output::writeUTF)
                    output.writeInt(packet.adminPublicKeys.size)
                    packet.adminPublicKeys.toSortedMap().forEach { (id, key) ->
                        output.writeUTF(id)
                        output.writeUTF(key)
                    }
                    output.writeUTF(packet.visibility)
                    output.writeBoolean(packet.requiresSignIn)
                    output.writeLong(packet.createdAt)
                    output.writeLong(packet.updatedAt)
                    output.writeBoolean(packet.deletedAt != null)
                    packet.deletedAt?.let(output::writeLong)
                    output.writeUTF(packet.signature)
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.EventAccessRequest -> {
                    output.writeInt(EVENT_ACCESS_REQUEST)
                    output.writeUTF(packet.requestId)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.userId)
                    output.writeUTF(packet.peerId)
                    output.writeUTF(packet.displayName)
                    output.writeLong(packet.requestedAt)
                    output.writeInt(packet.hopsRemaining)
                }

                is NearbyPacket.EventAccessGrant -> {
                    output.writeInt(EVENT_ACCESS_GRANT)
                    output.writeUTF(packet.grantId)
                    output.writeUTF(packet.requestId)
                    output.writeUTF(packet.eventId)
                    output.writeUTF(packet.userId)
                    output.writeUTF(packet.peerId)
                    output.writeUTF(packet.adminId)
                    output.writeLong(packet.issuedAt)
                    output.writeLong(packet.expiresAt)
                    output.writeUTF(packet.signature)
                    output.writeInt(packet.hopsRemaining)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): NearbyPacket? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            when (input.readInt()) {
                HELLO -> NearbyPacket.Hello(input.readUTF(), input.readUTF(), input.readUTF())
                MESSAGE -> NearbyPacket.Message(
                    messageId = input.readUTF(),
                    senderId = input.readUTF(),
                    senderName = input.readUTF(),
                    senderPhoneHash = input.readUTF(),
                    recipientId = input.readUTF(),
                    sentAt = input.readLong(),
                    text = input.readUTF(),
                    hopsRemaining = input.readInt(),
                    isGroup = input.readBoolean(),
                )

                ACKNOWLEDGEMENT -> NearbyPacket.Acknowledgement(
                    messageId = input.readUTF(),
                    senderId = input.readUTF(),
                    recipientId = input.readUTF(),
                    conversationId = input.readUTF(),
                    hopsRemaining = input.readInt(),
                    isGroup = input.readBoolean(),
                )

                GROUP_DEFINITION -> {
                    val groupId = input.readUTF()
                    val name = input.readUTF()
                    val ownerId = input.readUTF()
                    val createdAt = input.readLong()
                    val memberCount = input.readInt()
                    if (memberCount !in 1..MAX_GROUP_MEMBERS) return null
                    val members = List(memberCount) {
                        GroupMember(input.readUTF(), input.readUTF(), input.readUTF())
                    }
                    NearbyPacket.GroupDefinition(
                        groupId = groupId,
                        name = name,
                        ownerId = ownerId,
                        createdAt = createdAt,
                        members = members,
                        hopsRemaining = input.readInt(),
                    )
                }

                PEER_ANNOUNCEMENT -> NearbyPacket.PeerAnnouncement(
                    peerId = input.readUTF(),
                    name = input.readUTF(),
                    phoneHash = input.readUTF(),
                    hopsRemaining = input.readInt(),
                )

                EVENT_PRESENCE -> NearbyPacket.EventPresence(
                    eventId = input.readUTF(),
                    peerId = input.readUTF(),
                    name = input.readUTF(),
                    userId = input.readUTF(),
                    active = input.readBoolean(),
                    accessGranted = input.readBoolean(),
                )

                EVENT_CHAT_MESSAGE -> NearbyPacket.EventChatMessage(
                    messageId = input.readUTF(),
                    eventId = input.readUTF(),
                    senderId = input.readUTF(),
                    senderName = input.readUTF(),
                    text = input.readUTF(),
                    sentAt = input.readLong(),
                    hopsRemaining = input.readInt(),
                )

                EVENT_ANNOUNCEMENT -> NearbyPacket.EventAnnouncement(
                    announcementId = input.readUTF(),
                    eventId = input.readUTF(),
                    adminId = input.readUTF(),
                    adminName = input.readUTF(),
                    text = input.readUTF(),
                    createdAt = input.readLong(),
                    revision = input.readLong(),
                    signature = input.readUTF(),
                    hopsRemaining = input.readInt(),
                )

                EVENT_MUTATION -> {
                    val eventId = input.readUTF()
                    val adminId = input.readUTF()
                    val title = input.readUTF()
                    val description = input.readUTF()
                    val venueName = input.readUTF()
                    val latitude = input.readDouble()
                    val longitude = input.readDouble()
                    val radiusMetres = input.readDouble()
                    val startsAt = input.readLong()
                    val endsAt = input.readLong()
                    val createdBy = input.readUTF()
                    val adminCount = input.readInt()
                    if (adminCount !in 1..MAX_EVENT_ADMINS) return null
                    val adminIds = List(adminCount) { input.readUTF() }
                    val keyCount = input.readInt()
                    if (keyCount !in 0..MAX_EVENT_ADMINS) return null
                    val adminKeys = buildMap {
                        repeat(keyCount) { put(input.readUTF(), input.readUTF()) }
                    }
                    val visibility = input.readUTF()
                    val requiresSignIn = input.readBoolean()
                    val createdAt = input.readLong()
                    val updatedAt = input.readLong()
                    val deletedAt = if (input.readBoolean()) input.readLong() else null
                    NearbyPacket.EventMutation(
                        eventId,
                        adminId,
                        title,
                        description,
                        venueName,
                        latitude,
                        longitude,
                        radiusMetres,
                        startsAt,
                        endsAt,
                        createdBy,
                        adminIds,
                        adminKeys,
                        visibility,
                        requiresSignIn,
                        createdAt,
                        updatedAt,
                        deletedAt,
                        input.readUTF(),
                        input.readInt(),
                    )
                }

                EVENT_ACCESS_REQUEST -> NearbyPacket.EventAccessRequest(
                    requestId = input.readUTF(),
                    eventId = input.readUTF(),
                    userId = input.readUTF(),
                    peerId = input.readUTF(),
                    displayName = input.readUTF(),
                    requestedAt = input.readLong(),
                    hopsRemaining = input.readInt(),
                )

                EVENT_ACCESS_GRANT -> NearbyPacket.EventAccessGrant(
                    grantId = input.readUTF(),
                    requestId = input.readUTF(),
                    eventId = input.readUTF(),
                    userId = input.readUTF(),
                    peerId = input.readUTF(),
                    adminId = input.readUTF(),
                    issuedAt = input.readLong(),
                    expiresAt = input.readLong(),
                    signature = input.readUTF(),
                    hopsRemaining = input.readInt(),
                )

                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    private const val MAX_GROUP_MEMBERS = 100
    private const val MAX_EVENT_ADMINS = 100
}
