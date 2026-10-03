package com.example.blap.chat

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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
    @Volatile private var sessionGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val advertisingRetry = NearbyStartRetry { delay, action -> handler.postDelayed({ action() }, delay) }
    private val discoveryRetry = NearbyStartRetry { delay, action -> handler.postDelayed({ action() }, delay) }

    private fun payloadCallback(generation: Long) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (generation != sessionGeneration) return
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            listener?.onBytesReceived(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun connectionLifecycleCallback(generation: Long) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (generation != sessionGeneration) return
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.authenticationDigits, info.rawAuthenticationToken)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (generation != sessionGeneration) return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> listener?.onConnectionSucceeded(endpointId)
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED ->
                    listener?.onConnectionFailed(endpointId, "The other phone declined the connection.")
                else -> listener?.onConnectionFailed(endpointId, connectionFailureMessage(result.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (generation != sessionGeneration) return
            listener?.onDisconnected(endpointId)
        }
    }

    private fun endpointDiscoveryCallback(generation: Long) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!discoveryRetry.isCurrent(generation)) return
            listener?.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            if (!discoveryRetry.isCurrent(generation)) return
            listener?.onEndpointLost(endpointId)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAdvertising(endpointName: String, serviceId: String) {
        startAdvertising(endpointName, serviceId, advertisingRetry.begin(), 0)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(endpointName: String, serviceId: String, ticket: Long, attempt: Int) {
        if (!advertisingRetry.isCurrent(ticket)) return
        val generation = sessionGeneration
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startAdvertising(
                endpointName,
                serviceId,
                connectionLifecycleCallback(generation),
                options,
            ).addOnFailureListener { exception ->
                if (generation != sessionGeneration || !advertisingRetry.isCurrent(ticket)) return@addOnFailureListener
                if (exception.isRetryableStart()) {
                    connectionsClient.stopAdvertising()
                    if (advertisingRetry.retry(ticket, attempt) {
                            startAdvertising(endpointName, serviceId, ticket, attempt + 1)
                        }) return@addOnFailureListener
                }
                listener?.onUnavailable("Could not turn on nearby messaging: ${exception.readableMessage()}")
            }
        } catch (_: SecurityException) {
            listener?.onUnavailable("Allow nearby permissions to make this phone discoverable.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(serviceId: String) {
        startDiscovery(serviceId, discoveryRetry.begin(), 0)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(serviceId: String, ticket: Long, attempt: Int) {
        if (!discoveryRetry.isCurrent(ticket)) return
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        try {
            connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback(ticket), options)
                .addOnFailureListener { exception ->
                    if (!discoveryRetry.isCurrent(ticket)) return@addOnFailureListener
                    if (exception.isRetryableStart()) {
                        connectionsClient.stopDiscovery()
                        if (discoveryRetry.retry(ticket, attempt) {
                                startDiscovery(serviceId, ticket, attempt + 1)
                            }) return@addOnFailureListener
                    }
                    listener?.onUnavailable("Could not find nearby phones: ${exception.readableMessage()}")
                }
        } catch (_: SecurityException) {
            listener?.onUnavailable("Allow nearby permissions to find other phones.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestConnection(localEndpointName: String, endpointId: String) {
        val generation = sessionGeneration
        try {
            connectionsClient.requestConnection(
                localEndpointName,
                endpointId,
                connectionLifecycleCallback(generation),
            ).addOnFailureListener { exception ->
                if (generation != sessionGeneration) return@addOnFailureListener
                listener?.onConnectionFailed(endpointId, exception.connectionFailureMessage("Connection request"))
            }
        } catch (_: SecurityException) {
            listener?.onConnectionFailed(endpointId, "Nearby permissions are required to connect.")
        }
    }

    @SuppressLint("MissingPermission")
    override fun acceptConnection(endpointId: String) {
        val generation = sessionGeneration
        try {
            connectionsClient.acceptConnection(endpointId, payloadCallback(generation))
                .addOnFailureListener { exception ->
                    if (generation != sessionGeneration) return@addOnFailureListener
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
        send(endpointId, bytes) {}
    }

    override fun send(endpointId: String, bytes: ByteArray, onSuccess: () -> Unit) {
        val generation = sessionGeneration
        try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnSuccessListener { if (generation == sessionGeneration) onSuccess() }
                .addOnFailureListener { exception ->
                    if (generation != sessionGeneration) return@addOnFailureListener
                    val status = (exception as? ApiException)?.statusCode
                    val message = exception.connectionFailureMessage("Message")
                    if (status == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR || status == ConnectionsStatusCodes.STATUS_RADIO_ERROR)
                        listener?.onConnectionFailed(endpointId, message)
                    else listener?.onError(message)
                }
        } catch (_: SecurityException) {
            listener?.onError("Nearby permissions are required to send messages.")
        }
    }

    override fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    override fun stopAdvertising() {
        advertisingRetry.cancel()
        connectionsClient.stopAdvertising()
    }

    override fun stopDiscovery() {
        discoveryRetry.cancel()
        connectionsClient.stopDiscovery()
    }

    override fun disconnectAll() {
        sessionGeneration++
        advertisingRetry.cancel()
        discoveryRetry.cancel()
        connectionsClient.stopAllEndpoints()
    }

    private fun Exception.readableMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: "unknown Nearby error"

    private fun Exception.isRetryableStart(): Boolean = (this as? ApiException)?.statusCode in setOf(
        ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
        ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
        ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL,
        ConnectionsStatusCodes.STATUS_RADIO_ERROR,
    )

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
