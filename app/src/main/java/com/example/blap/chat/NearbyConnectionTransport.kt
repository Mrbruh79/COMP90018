package com.example.blap.chat

/** Byte-oriented Nearby Connections session. Packet encoding and mesh routing stay elsewhere. */
interface NearbyConnectionTransport {
    var listener: Listener?

    fun startAdvertising(endpointName: String, serviceId: String)
    fun startDiscovery(serviceId: String)
    fun requestConnection(localEndpointName: String, endpointId: String)
    fun acceptConnection(endpointId: String)
    fun rejectConnection(endpointId: String)
    fun send(endpointId: String, bytes: ByteArray)
    fun send(endpointId: String, bytes: ByteArray, onSuccess: () -> Unit)
    fun disconnect(endpointId: String)
    fun stopAdvertising()
    fun stopDiscovery()
    fun disconnectAll()

    interface Listener {
        fun onEndpointFound(endpointId: String, endpointName: String)
        fun onEndpointLost(endpointId: String)
        fun onConnectionInitiated(endpointId: String, endpointName: String, authenticationDigits: String)
        fun onConnectionSucceeded(endpointId: String)
        fun onConnectionFailed(endpointId: String, message: String)
        fun onDisconnected(endpointId: String)
        fun onBytesReceived(endpointId: String, bytes: ByteArray)
        fun onError(message: String)
        fun onUnavailable(message: String)
    }
}
