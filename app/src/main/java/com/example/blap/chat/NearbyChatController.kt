package com.example.blap.chat

import com.example.blap.event.EventAnnouncement
import com.example.blap.event.EventChatMessage
import com.example.blap.event.EventMutation
import com.example.blap.event.EventAccessGrant
import com.example.blap.event.EventAccessRequest

interface NearbyChatController {
    var listener: Listener?

    fun startAdvertising(displayName: String, peerId: String, phoneHash: String)
    fun startDiscovery()
    fun connectToDevice(endpointId: String)
    fun sendMessage(message: OutgoingNearbyMessage)
    fun publishGroup(group: PrivateGroup)
    fun synchronizeGroups(
        peerId: String,
        groups: List<PrivateGroup>,
        messages: List<StoredGroupMessage>,
    )
    fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String)
    fun setActiveEvent(
        eventId: String?,
        meshSecret: String = "",
        userId: String = "",
        accessGranted: Boolean = true,
    ) = Unit
    fun sendEventChatMessage(message: EventChatMessage) = Unit
    fun sendEventAnnouncement(announcement: EventAnnouncement) = Unit
    fun sendEventMutation(mutation: EventMutation) = Unit
    fun sendEventAccessRequest(request: EventAccessRequest) = Unit
    fun sendEventAccessGrant(grant: EventAccessGrant) = Unit
    fun synchronizeEventHistory(peerId: String, messages: List<EventChatMessage>) = Unit
    fun synchronizeEventAnnouncements(peerId: String, announcements: List<EventAnnouncement>) = Unit
    fun disconnect(peerId: String)
    fun stop()
    fun close()

    interface Listener {
        fun onDeviceFound(device: NearbyDevice)
        fun onDeviceLost(endpointId: String)
        fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String)
        fun onConnected(peer: ConnectedPeer)
        fun onMeshPeerFound(peer: GroupMember)
        fun onGroupReceived(group: PrivateGroup)
        fun onMessageReceived(message: IncomingNearbyMessage)
        fun onMessageSent(peerId: String, messageId: String)
        fun onMessageDelivered(peerId: String, messageId: String)
        fun onEventPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) = Unit
        fun onEventChatMessageReceived(message: EventChatMessage) = Unit
        fun onEventAnnouncementReceived(announcement: EventAnnouncement) = Unit
        fun onEventMutationReceived(mutation: EventMutation) = Unit
        fun onEventAccessRequestReceived(request: EventAccessRequest) = Unit
        fun onEventAccessGrantReceived(grant: EventAccessGrant) = Unit
        fun onEventMessageSent(eventId: String, messageId: String) = Unit
        fun onDisconnected(peerId: String)
        fun onError(message: String)
        fun onNearbyUnavailable(message: String) = onError(message)
    }
}
