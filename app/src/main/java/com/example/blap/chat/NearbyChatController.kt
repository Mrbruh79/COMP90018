package com.example.blap.chat

interface NearbyChatController {
    var listener: Listener?

    fun startAdvertising(displayName: String, peerId: String, phoneHash: String, meshToken: String)
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
        fun onDisconnected(peerId: String)
        fun onError(message: String)
        fun onNearbyUnavailable(message: String) = onError(message)
    }
}
