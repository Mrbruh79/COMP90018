package com.example.blap.chat

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets

class NearbyChatManager(context: Context) : NearbyChatController {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val knownDevices = mutableMapOf<String, NearbyDevice>()

    private var localDisplayName = ""
    private var pendingEndpointId: String? = null
    private var pendingDevice: NearbyDevice? = null
    private var connectedEndpointId: String? = null

    override var listener: NearbyChatController.Listener? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId != connectedEndpointId || payload.type != Payload.Type.BYTES) return

            payload.asBytes()?.let { bytes ->
                listener?.onMessageReceived(String(bytes, StandardCharsets.UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate,
        ) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val device = NearbyDevice(endpointId, info.endpointName)
            val otherPendingEndpoint = pendingEndpointId?.takeIf { it != endpointId }
            if (connectedEndpointId != null || otherPendingEndpoint != null) {
                connectionsClient.rejectConnection(endpointId)
                return
            }

            pendingEndpointId = endpointId
            pendingDevice = device
            // Discovery competes for the same radios used to negotiate the direct link.
            stopScanning()
            listener?.onConnectionInitiated(device, info.authenticationDigits)
            acceptConnection(endpointId)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (endpointId != pendingEndpointId) return

            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    connectedEndpointId = endpointId
                    val device = pendingDevice ?: knownDevices[endpointId]
                        ?: NearbyDevice(endpointId, "Nearby device")
                    pendingEndpointId = null
                    pendingDevice = null
                    stopScanning()
                    listener?.onConnected(device)
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    clearPendingConnection()
                    listener?.onError("The other device declined the connection.")
                }

                else -> {
                    clearPendingConnection()
                    listener?.onError(connectionFailureMessage(result.status.statusCode))
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != connectedEndpointId) return
            connectedEndpointId = null
            listener?.onDisconnected()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val device = NearbyDevice(endpointId, info.endpointName)
            knownDevices[endpointId] = device
            listener?.onDeviceFound(device)
        }

        override fun onEndpointLost(endpointId: String) {
            knownDevices.remove(endpointId)
            listener?.onDeviceLost(endpointId)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(displayName: String) {
        localDisplayName = displayName
        knownDevices.clear()
        clearPendingConnection()
        connectionsClient.stopAdvertising()

        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient
                .startAdvertising(
                    displayName,
                    SERVICE_ID,
                    connectionLifecycleCallback,
                    options,
                )
                .addOnFailureListener { exception ->
                    listener?.onError("Advertising failed: ${exception.readableMessage()}")
                }
        } catch (exception: SecurityException) {
            listener?.onError("Nearby permissions are required to advertise this phone.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        connectionsClient.stopDiscovery()
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
                .addOnFailureListener { exception ->
                    listener?.onError("Discovery failed: ${exception.readableMessage()}")
                }
        } catch (exception: SecurityException) {
            listener?.onError("Nearby permissions are required to discover other phones.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToDevice(endpointId: String) {
        if (connectedEndpointId != null || pendingEndpointId != null) return

        val device = knownDevices[endpointId] ?: return
        pendingEndpointId = endpointId
        pendingDevice = device
        stopScanning()
        try {
            connectionsClient
                .requestConnection(localDisplayName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { exception ->
                    clearPendingConnection()
                    listener?.onError(exception.connectionFailureMessage("Connection request"))
                }
        } catch (exception: SecurityException) {
            clearPendingConnection()
            listener?.onError("Nearby permissions are required to connect.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendMessage(message: String) {
        val endpointId = connectedEndpointId
        if (endpointId == null) {
            listener?.onError("Connect to a nearby device before sending a message.")
            return
        }

        try {
            connectionsClient
                .sendPayload(
                    endpointId,
                    Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8)),
                )
                .addOnSuccessListener { listener?.onMessageSent(message) }
                .addOnFailureListener { exception ->
                    listener?.onError(exception.connectionFailureMessage("Message"))
                }
        } catch (exception: SecurityException) {
            listener?.onError("Nearby permissions are required to send messages.")
        }
    }

    override fun disconnect() {
        val endpointId = connectedEndpointId ?: pendingEndpointId
        if (endpointId != null) connectionsClient.disconnectFromEndpoint(endpointId)
        connectedEndpointId = null
        clearPendingConnection()
        stopScanning()
        listener?.onDisconnected()
    }

    override fun close() {
        stopScanning()
        connectionsClient.stopAllEndpoints()
        knownDevices.clear()
        connectedEndpointId = null
        clearPendingConnection()
        listener = null
    }

    @SuppressLint("MissingPermission")
    private fun acceptConnection(endpointId: String) {
        try {
            connectionsClient
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { exception ->
                    clearPendingConnection()
                    listener?.onError(exception.connectionFailureMessage("Accepting the connection"))
                }
        } catch (exception: SecurityException) {
            clearPendingConnection()
            listener?.onError("Nearby permissions are required to accept a connection.")
        }
    }

    private fun stopScanning() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    private fun clearPendingConnection() {
        pendingEndpointId = null
        pendingDevice = null
    }

    private fun Exception.readableMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "unknown Nearby error"

    private fun Exception.connectionFailureMessage(action: String): String {
        val statusCode = (this as? ApiException)?.statusCode
        return if (statusCode != null) {
            connectionFailureMessage(statusCode)
        } else {
            "$action failed: ${readableMessage()}"
        }
    }

    private fun connectionFailureMessage(statusCode: Int): String = when (statusCode) {
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR ->
            "The direct link dropped during setup. Keep Wi-Fi and Bluetooth on, then restart " +
                "nearby chat on both phones and connect from only one phone."

        ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
            "A phone radio could not start the link. Toggle Wi-Fi and Bluetooth, then try again."

        else -> "The connection could not be established " +
            "(${ConnectionsStatusCodes.getStatusCodeString(statusCode)}). Please try again."
    }

    private companion object {
        const val SERVICE_ID = "com.example.blap"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
