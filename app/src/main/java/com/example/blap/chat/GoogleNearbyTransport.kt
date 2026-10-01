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

/** Android Nearby Connections adapter. Permission checks remain with the caller that starts discovery. */
class GoogleNearbyTransport(context: Context) : NearbyConnectionTransport {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    override var listener: NearbyConnectionTransport.Listener? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            listener?.onBytesReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.authenticationDigits)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> listener?.onConnectionSucceeded(endpointId)
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    listener?.onConnectionFailed(endpointId, "The other phone declined the connection.")
                else -> listener?.onConnectionFailed(endpointId, connectionFailureMessage(result.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            listener?.onEndpointLost(endpointId)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(endpointName: String, serviceId: String) {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startAdvertising(
                endpointName,
                serviceId,
                connectionLifecycleCallback,
                options,
            ).addOnFailureListener { exception ->
                listener?.onUnavailable("Could not turn on nearby messaging: ${exception.readableMessage()}")
            }
        } catch (_: SecurityException) {
            listener?.onUnavailable("Allow nearby permissions to make this phone discoverable.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(serviceId: String) {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
                .addOnFailureListener { exception ->
                    listener?.onUnavailable("Could not find nearby phones: ${exception.readableMessage()}")
                }
        } catch (_: SecurityException) {
            listener?.onUnavailable("Allow nearby permissions to find other phones.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestConnection(localEndpointName: String, endpointId: String) {
        try {
            connectionsClient.requestConnection(
                localEndpointName,
                endpointId,
                connectionLifecycleCallback,
            ).addOnFailureListener { exception ->
                listener?.onConnectionFailed(endpointId, exception.connectionFailureMessage("Connection request"))
            }
        } catch (_: SecurityException) {
            listener?.onConnectionFailed(endpointId, "Nearby permissions are required to connect.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun acceptConnection(endpointId: String) {
        try {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { exception ->
                    listener?.onConnectionFailed(
                        endpointId,
                        exception.connectionFailureMessage("Accepting the connection"),
                    )
                }
        } catch (_: SecurityException) {
            listener?.onConnectionFailed(endpointId, "Nearby permissions are required to accept a connection.")
        }
    }

    override fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to use this connection.")
        }
    }

    override fun send(endpointId: String, bytes: ByteArray, onSuccess: () -> Unit) {
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { exception ->
                    listener?.onError(exception.connectionFailureMessage("Message"))
                }
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to send messages.")
        }
    }

    override fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    override fun stopAdvertising() {
        connectionsClient.stopAdvertising()
    }

    override fun stopDiscovery() {
        connectionsClient.stopDiscovery()
    }

    override fun disconnectAll() {
        connectionsClient.stopAllEndpoints()
    }

    private fun Exception.readableMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "unknown Nearby error"

    private fun Exception.connectionFailureMessage(action: String): String {
        val statusCode = (this as? ApiException)?.statusCode
        return if (statusCode == null) "$action failed: ${readableMessage()}"
        else connectionFailureMessage(statusCode)
    }

    private fun connectionFailureMessage(statusCode: Int): String = when (statusCode) {
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR ->
            "The direct link dropped. Keep Wi-Fi and Bluetooth on and try again."

        ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
            "A phone radio could not start the link. Toggle Wi-Fi and Bluetooth and try again."

        else -> "The connection failed (${ConnectionsStatusCodes.getStatusCodeString(statusCode)})."
    }

    companion object {
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
