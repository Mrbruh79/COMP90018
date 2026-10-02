package com.example.blap.chat

import com.example.blap.event.CommunityEvent
import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation
import com.example.blap.event.EventVisibility

data class MeshContext(
    val localPeerId: String,
    val fromEndpointId: String,
    val fromPeer: ConnectedPeer?,
    val isEventEndpoint: Boolean,
    val activeEventId: String?,
    val accessGranted: Boolean,
    val chatForwardTargets: Set<String>,
    val eventForwardTargets: Set<String>,
)

sealed interface MeshLocalDelivery {
    data class ChatMessage(val message: IncomingNearbyMessage) : MeshLocalDelivery
    data class Acknowledged(val conversationId: String, val messageId: String) : MeshLocalDelivery
    data class Group(val group: PrivateGroup) : MeshLocalDelivery
    data class MeshPeer(val peer: GroupMember) : MeshLocalDelivery
    data class EventPeer(
        val peerId: String,
        val eventId: String,
        val userId: String,
        val accessGranted: Boolean,
    ) : MeshLocalDelivery
    data class EventChat(val message: EventChatMessage) : MeshLocalDelivery
    data class IncomingEventAnnouncement(val announcement: EventAnnouncement) : MeshLocalDelivery
    data class IncomingEventMutation(val mutation: EventMutation) : MeshLocalDelivery
}

data class MeshDecision(
    val deliveries: List<MeshLocalDelivery> = emptyList(),
    val forwardPacket: NearbyPacket? = null,
    val forwardTo: Set<String> = emptySet(),
) {
    val isDrop: Boolean
        get() = deliveries.isEmpty() && forwardPacket == null

    companion object {
        val Drop = MeshDecision()
    }
}

/** Hop limits, duplicate suppression and event/chat isolation for decoded Nearby packets. */
class MeshRouter {
    private val seenMessageIds = boundedIdSet()
    private val seenAcknowledgements = boundedIdSet()
    private val seenGroupDefinitions = boundedIdSet()
    private val seenEventMessageIds = boundedIdSet()
    private val seenEventAnnouncementIds = boundedIdSet()
    private val seenEventMutationIds = boundedIdSet()
    private val knownMeshPeers = mutableMapOf<String, GroupMember>()
    private val cachedGroupDefinitions = linkedMapOf<String, NearbyPacket.GroupDefinition>()
    private val cachedGroupMessages = linkedMapOf<String, NearbyPacket.Message>()

    fun noteOutgoingMessage(messageId: String) {
        rememberId(seenMessageIds, messageId)
    }

    fun noteOutgoingAcknowledgement(packet: NearbyPacket.Acknowledgement) {
        rememberId(seenAcknowledgements, acknowledgementId(packet))
    }

    fun noteOutgoingGroup(packet: NearbyPacket.GroupDefinition) {
        rememberId(seenGroupDefinitions, groupDefinitionId(packet))
        cachedGroupDefinitions[packet.groupId] = packet.copy(hopsRemaining = MAX_HOPS)
    }

    fun noteOutgoingEventMessage(messageId: String) {
        rememberId(seenEventMessageIds, messageId)
    }

    fun noteOutgoingEventAnnouncement(packet: NearbyPacket.EventAnnouncement) {
        rememberId(seenEventAnnouncementIds, eventAnnouncementKey(packet))
    }

    fun noteOutgoingEventMutation(packet: NearbyPacket.EventMutation) {
        rememberId(seenEventMutationIds, eventMutationKey(packet))
    }

    fun rememberGroupMessage(packet: NearbyPacket.Message) {
        cachedGroupMessages[packet.messageId] = packet.copy(hopsRemaining = MAX_HOPS)
        if (cachedGroupMessages.size > MAX_CACHED_GROUP_MESSAGES) {
            cachedGroupMessages.remove(cachedGroupMessages.keys.first())
        }
    }

    fun rememberPeer(peer: GroupMember) {
        knownMeshPeers[peer.peerId] = peer
    }

    fun resetKnownPeers() {
        knownMeshPeers.clear()
    }

    fun knownPeers(): Collection<GroupMember> = knownMeshPeers.values.toList()

    fun cachedGroupDefinitions(): Collection<NearbyPacket.GroupDefinition> = cachedGroupDefinitions.values.toList()

    fun cachedGroupMessages(): Collection<NearbyPacket.Message> = cachedGroupMessages.values.toList()

    fun ingest(packet: NearbyPacket, context: MeshContext): MeshDecision = when (packet) {
        is NearbyPacket.Hello -> MeshDecision.Drop
        is NearbyPacket.Message -> handleMessage(packet, context)
        is NearbyPacket.Acknowledgement -> handleAcknowledgement(packet, context)
        is NearbyPacket.GroupDefinition -> handleGroupDefinition(packet, context)
        is NearbyPacket.PeerAnnouncement -> handlePeerAnnouncement(packet, context)
        is NearbyPacket.EventPresence -> handleEventPresence(packet, context)
        is NearbyPacket.EventChatMessage -> handleEventChatMessage(packet, context)
        is NearbyPacket.EventAnnouncement -> handleEventAnnouncement(packet, context)
        is NearbyPacket.EventMutation -> handleEventMutation(packet, context)
    }

    fun clear() {
        seenMessageIds.clear()
        seenAcknowledgements.clear()
        seenGroupDefinitions.clear()
        seenEventMessageIds.clear()
        seenEventAnnouncementIds.clear()
        seenEventMutationIds.clear()
        knownMeshPeers.clear()
        cachedGroupDefinitions.clear()
        cachedGroupMessages.clear()
    }

    private fun handleMessage(packet: NearbyPacket.Message, context: MeshContext): MeshDecision {
        if (context.isEventEndpoint) return MeshDecision.Drop
        val peer = context.fromPeer ?: return MeshDecision.Drop
        if (packet.messageId.isBlank() || packet.text.isBlank()) return MeshDecision.Drop
        if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop

        if (packet.isGroup) {
            val firstSeen = rememberId(seenMessageIds, packet.messageId)
            if (firstSeen) rememberGroupMessage(packet)
            val delivery = MeshLocalDelivery.ChatMessage(
                IncomingNearbyMessage(
                    messageId = packet.messageId,
                    conversationId = packet.recipientId,
                    senderId = packet.senderId,
                    senderName = packet.senderName.take(24),
                    senderPhoneHash = packet.senderPhoneHash,
                    text = packet.text.take(1_000),
                    sentAt = packet.sentAt,
                ),
            )
            return if (firstSeen && packet.hopsRemaining > 0) {
                MeshDecision(
                    deliveries = listOf(delivery),
                    forwardPacket = packet.copy(hopsRemaining = packet.hopsRemaining - 1),
                    forwardTo = context.chatForwardTargets,
                )
            } else {
                MeshDecision(deliveries = listOf(delivery))
            }
        }

        if (packet.senderId != peer.peerId || packet.recipientId != context.localPeerId) return MeshDecision.Drop
        return MeshDecision(
            deliveries = listOf(
                MeshLocalDelivery.ChatMessage(
                    IncomingNearbyMessage(
                        messageId = packet.messageId,
                        conversationId = packet.senderId,
                        senderId = packet.senderId,
                        senderName = peer.name,
                        senderPhoneHash = peer.phoneHash,
                        text = packet.text.take(1_000),
                        sentAt = packet.sentAt,
                    ),
                ),
            ),
        )
    }

    private fun handleAcknowledgement(
        packet: NearbyPacket.Acknowledgement,
        context: MeshContext,
    ): MeshDecision {
        if (context.isEventEndpoint) return MeshDecision.Drop
        val peer = context.fromPeer ?: return MeshDecision.Drop
        if (packet.isGroup) {
            if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop
            if (!rememberId(seenAcknowledgements, acknowledgementId(packet))) return MeshDecision.Drop
            val deliveries = if (packet.recipientId == context.localPeerId) {
                listOf(MeshLocalDelivery.Acknowledged(packet.conversationId, packet.messageId))
            } else {
                emptyList()
            }
            return if (packet.hopsRemaining > 0) {
                MeshDecision(
                    deliveries = deliveries,
                    forwardPacket = packet.copy(hopsRemaining = packet.hopsRemaining - 1),
                    forwardTo = context.chatForwardTargets,
                )
            } else {
                MeshDecision(deliveries = deliveries)
            }
        }

        if (packet.senderId != peer.peerId || packet.recipientId != context.localPeerId) return MeshDecision.Drop
        return MeshDecision(
            deliveries = listOf(MeshLocalDelivery.Acknowledged(packet.conversationId, packet.messageId)),
        )
    }

    private fun handleGroupDefinition(
        packet: NearbyPacket.GroupDefinition,
        context: MeshContext,
    ): MeshDecision {
        if (context.isEventEndpoint) return MeshDecision.Drop
        if (context.fromPeer == null) return MeshDecision.Drop
        if (packet.groupId.isBlank() || packet.name.isBlank() || packet.ownerId.isBlank()) return MeshDecision.Drop
        if (packet.members.isEmpty() || packet.members.size > MAX_GROUP_MEMBERS) return MeshDecision.Drop
        if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop
        if (packet.members.any { it.peerId.isBlank() || it.name.isBlank() }) return MeshDecision.Drop
        if (!rememberId(seenGroupDefinitions, groupDefinitionId(packet))) return MeshDecision.Drop
        cachedGroupDefinitions[packet.groupId] = packet.copy(hopsRemaining = MAX_HOPS)
        val delivery = MeshLocalDelivery.Group(
            PrivateGroup(
                id = packet.groupId,
                name = packet.name.take(40),
                ownerId = packet.ownerId,
                createdAt = packet.createdAt,
                members = packet.members.map { it.copy(name = it.name.take(24)) },
            ),
        )
        return if (packet.hopsRemaining > 0) {
            MeshDecision(
                deliveries = listOf(delivery),
                forwardPacket = packet.copy(hopsRemaining = packet.hopsRemaining - 1),
                forwardTo = context.chatForwardTargets,
            )
        } else {
            MeshDecision(deliveries = listOf(delivery))
        }
    }

    private fun handlePeerAnnouncement(
        packet: NearbyPacket.PeerAnnouncement,
        context: MeshContext,
    ): MeshDecision {
        if (context.isEventEndpoint) return MeshDecision.Drop
        if (context.fromPeer == null) return MeshDecision.Drop
        if (packet.peerId.isBlank() || packet.name.isBlank() || packet.peerId == context.localPeerId) {
            return MeshDecision.Drop
        }
        if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop
        val peer = GroupMember(packet.peerId, packet.name.take(24), packet.phoneHash)
        if (knownMeshPeers.put(peer.peerId, peer) == peer) return MeshDecision.Drop
        val delivery = MeshLocalDelivery.MeshPeer(peer)
        return if (packet.hopsRemaining > 0) {
            MeshDecision(
                deliveries = listOf(delivery),
                forwardPacket = packet.copy(
                    name = peer.name,
                    phoneHash = peer.phoneHash,
                    hopsRemaining = packet.hopsRemaining - 1,
                ),
                forwardTo = context.chatForwardTargets,
            )
        } else {
            MeshDecision(deliveries = listOf(delivery))
        }
    }

    private fun handleEventPresence(
        packet: NearbyPacket.EventPresence,
        context: MeshContext,
    ): MeshDecision {
        if (!context.isEventEndpoint) return MeshDecision.Drop
        val peer = context.fromPeer ?: return MeshDecision.Drop
        if (!packet.active || packet.eventId.isBlank()) return MeshDecision.Drop
        if (packet.peerId != peer.peerId) return MeshDecision.Drop
        if (packet.eventId != context.activeEventId) return MeshDecision.Drop
        return MeshDecision(
            deliveries = listOf(
                MeshLocalDelivery.EventPeer(peer.peerId, packet.eventId, packet.userId, packet.accessGranted),
            ),
        )
    }

    private fun handleEventChatMessage(
        packet: NearbyPacket.EventChatMessage,
        context: MeshContext,
    ): MeshDecision {
        if (!context.accessGranted) return MeshDecision.Drop
        if (!context.isEventEndpoint) return MeshDecision.Drop
        if (context.fromPeer == null) return MeshDecision.Drop
        if (packet.messageId.isBlank() || packet.eventId.isBlank() || packet.senderId.isBlank() ||
            packet.senderName.isBlank() || packet.text.isBlank()
        ) return MeshDecision.Drop
        if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop
        if (packet.eventId != context.activeEventId) return MeshDecision.Drop
        if (!rememberId(seenEventMessageIds, packet.messageId)) return MeshDecision.Drop
        val delivery = MeshLocalDelivery.EventChat(
            EventChatMessage(
                id = packet.messageId,
                eventId = packet.eventId,
                senderId = packet.senderId,
                senderName = packet.senderName.take(24),
                text = packet.text.take(MAX_EVENT_MESSAGE_LENGTH),
                createdAt = packet.sentAt,
            ),
        )
        return forwardEvent(delivery, packet.hopsRemaining, context) {
            packet.copy(hopsRemaining = packet.hopsRemaining - 1)
        }
    }

    private fun handleEventAnnouncement(
        packet: NearbyPacket.EventAnnouncement,
        context: MeshContext,
    ): MeshDecision {
        if (!context.accessGranted) return MeshDecision.Drop
        if (!context.isEventEndpoint) return MeshDecision.Drop
        if (context.fromPeer == null) return MeshDecision.Drop
        if (packet.announcementId.isBlank() || packet.eventId.isBlank() || packet.adminId.isBlank() ||
            packet.adminName.isBlank() || packet.text.isBlank()
        ) return MeshDecision.Drop
        if (packet.hopsRemaining !in 0..MAX_HOPS) return MeshDecision.Drop
        if (packet.eventId != context.activeEventId) return MeshDecision.Drop
        if (!rememberId(seenEventAnnouncementIds, eventAnnouncementKey(packet))) return MeshDecision.Drop
        val delivery = MeshLocalDelivery.IncomingEventAnnouncement(
            EventAnnouncement(
                id = packet.announcementId,
                eventId = packet.eventId,
                adminId = packet.adminId,
                adminName = packet.adminName.take(24),
                text = packet.text.take(MAX_EVENT_MESSAGE_LENGTH),
                createdAt = packet.createdAt,
                revision = packet.revision,
                signature = packet.signature,
                syncedToCloud = false,
            ),
        )
        return forwardEvent(delivery, packet.hopsRemaining, context) {
            packet.copy(hopsRemaining = packet.hopsRemaining - 1)
        }
    }

    private fun handleEventMutation(
        packet: NearbyPacket.EventMutation,
        context: MeshContext,
    ): MeshDecision {
        if (!context.accessGranted) return MeshDecision.Drop
        if (!context.isEventEndpoint) return MeshDecision.Drop
        if (context.fromPeer == null) return MeshDecision.Drop
        if (packet.eventId.isBlank() || packet.adminId.isBlank() || packet.title.isBlank() ||
            packet.createdBy.isBlank() || packet.signature.isBlank()
        ) return MeshDecision.Drop
        if (packet.hopsRemaining !in 0..MAX_HOPS || packet.eventId != context.activeEventId) return MeshDecision.Drop
        if (!rememberId(seenEventMutationIds, eventMutationKey(packet))) return MeshDecision.Drop
        val event = runCatching {
            CommunityEvent(
                id = packet.eventId,
                title = packet.title.take(80),
                description = packet.description.take(1_000),
                venueName = packet.venueName.take(200),
                latitude = packet.latitude,
                longitude = packet.longitude,
                radiusMetres = packet.radiusMetres,
                startsAt = packet.startsAt,
                endsAt = packet.endsAt,
                createdBy = packet.createdBy,
                adminIds = packet.adminIds.toSet(),
                memberIds = packet.adminIds.toSet(),
                adminPublicKeys = packet.adminPublicKeys,
                visibility = EventVisibility.valueOf(packet.visibility),
                requiresSignIn = packet.requiresSignIn,
                venueCheckInPayload = packet.venueCheckInPayload,
                privateMeshSecret = if (packet.visibility == EventVisibility.PRIVATE.name) {
                    "pending-verification"
                } else "",
                createdAt = packet.createdAt,
                updatedAt = packet.updatedAt,
                deletedAt = packet.deletedAt,
            )
        }.getOrNull() ?: return MeshDecision.Drop
        val delivery = MeshLocalDelivery.IncomingEventMutation(
            EventMutation(event, packet.adminId, packet.signature),
        )
        return forwardEvent(delivery, packet.hopsRemaining, context) {
            packet.copy(hopsRemaining = packet.hopsRemaining - 1)
        }
    }

    private fun forwardEvent(
        delivery: MeshLocalDelivery,
        hopsRemaining: Int,
        context: MeshContext,
        decremented: () -> NearbyPacket,
    ): MeshDecision = if (hopsRemaining > 0) {
        MeshDecision(
            deliveries = listOf(delivery),
            forwardPacket = decremented(),
            forwardTo = context.eventForwardTargets,
        )
    } else {
        MeshDecision(deliveries = listOf(delivery))
    }

    private fun rememberId(ids: LinkedHashSet<String>, id: String): Boolean {
        if (!ids.add(id)) return false
        if (ids.size > MAX_REMEMBERED_IDS) ids.remove(ids.first())
        return true
    }

    private fun acknowledgementId(packet: NearbyPacket.Acknowledgement): String =
        "${packet.messageId}:${packet.senderId}"

    private fun groupDefinitionId(packet: NearbyPacket.GroupDefinition): String =
        "${packet.groupId}:${packet.createdAt}"

    private fun eventAnnouncementKey(packet: NearbyPacket.EventAnnouncement): String =
        "${packet.announcementId}:${packet.revision}"

    private fun eventMutationKey(packet: NearbyPacket.EventMutation): String =
        "${packet.eventId}:${packet.updatedAt}"

    private fun boundedIdSet() = LinkedHashSet<String>()

    companion object {
        const val MAX_HOPS = 16
        const val MAX_REMEMBERED_IDS = 10_000
        const val MAX_GROUP_MEMBERS = 100
        const val MAX_CACHED_GROUP_MESSAGES = 2_000
        const val MAX_EVENT_MESSAGE_LENGTH = 1_000
    }
}
