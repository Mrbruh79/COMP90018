package com.example.blap.chat

interface NearbyChatController {
    var listener: Listener?

    fun startAdvertising(displayName: String)

    fun startDiscovery()

    fun connectToDevice(endpointId: String)

    fun sendMessage(message: String)

    fun disconnect()

    fun close()

    interface Listener {
        fun onDeviceFound(device: NearbyDevice)

        fun onDeviceLost(endpointId: String)

        fun onConnectionInitiated(device: NearbyDevice, authenticationDigits: String)

        fun onConnected(device: NearbyDevice)

        fun onMessageReceived(message: String)

        fun onMessageSent(message: String)

        fun onDisconnected()

        fun onError(message: String)
    }
}
