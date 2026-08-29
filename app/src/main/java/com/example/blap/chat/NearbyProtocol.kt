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
}

object NearbyProtocol {
    private const val MAGIC = 0x424C4150
    private const val VERSION = 5
    private const val HELLO = 1
    private const val MESSAGE = 2
    private const val ACKNOWLEDGEMENT = 3
    private const val GROUP_DEFINITION = 4
    private const val PEER_ANNOUNCEMENT = 5

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

                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    private const val MAX_GROUP_MEMBERS = 100
}
