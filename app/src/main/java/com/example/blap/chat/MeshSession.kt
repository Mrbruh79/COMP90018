package com.example.blap.chat

sealed interface MeshEvent {
    data class Send(val packet: NearbyPacket) : MeshEvent
    data object Established : MeshEvent
    data class Packet(val packet: NearbyPacket) : MeshEvent
    data object Rejected : MeshEvent
}

class MeshSession(
    private val token: String,
    private val localPeerId: String,
    private val randomBytes: (Int) -> ByteArray = { MeshCrypto.randomBytes(it) },
) {
    var isEstablished: Boolean = false
        private set
    var isRejected: Boolean = false
        private set

    private var localNonce: ByteArray? = null
    private var remoteNonce: ByteArray? = null
    private var remotePeerId: String? = null
    private var sessionKey: ByteArray? = null
    private var pendingAuth: NearbyPacket.HandshakeAuth? = null
    private val pendingPackets = mutableListOf<NearbyPacket>()
    private val seenIvHex = LinkedHashSet<String>()

    fun noncePacket(): NearbyPacket.HandshakeNonce {
        val nonce = localNonce ?: randomBytes(MeshCrypto.NONCE_SIZE).also { localNonce = it }
        return NearbyPacket.HandshakeNonce(localPeerId, MeshCrypto.toHex(nonce))
    }

    fun encode(packet: NearbyPacket): ByteArray {
        if (packet is NearbyPacket.HandshakeNonce || packet is NearbyPacket.HandshakeAuth) {
            return NearbyProtocol.encode(packet)
        }
        val key = sessionKey
        check(isEstablished && key != null) { "mesh handshake is not complete" }
        return NearbyProtocol.encodeSealed(
            MeshCrypto.encryptAesGcm(key, NearbyProtocol.encode(packet), randomBytes(MeshCrypto.IV_SIZE)),
        )
    }

    fun receive(bytes: ByteArray): List<MeshEvent> {
        if (isRejected) return emptyList()
        return when (val frame = NearbyProtocol.decodeFrame(bytes) ?: return emptyList()) {
            is NearbyFrame.Clear -> when (val packet = frame.packet) {
                is NearbyPacket.HandshakeNonce -> onNonce(packet)
                is NearbyPacket.HandshakeAuth -> onAuth(packet)
                else -> emptyList()
            }

            is NearbyFrame.Sealed -> onSealed(frame.payload)
        }
    }

    private fun onNonce(packet: NearbyPacket.HandshakeNonce): List<MeshEvent> {
        if (packet.peerId.isBlank() || packet.peerId == localPeerId) return emptyList()
        val nonce = MeshCrypto.fromHex(packet.nonceHex) ?: return reject()
        if (remoteNonce != null) return emptyList()

        ensureLocalNonce()
        remotePeerId = packet.peerId
        remoteNonce = nonce
        sessionKey = MeshCrypto.sessionKey(token, localNonce(), nonce)

        val events = mutableListOf<MeshEvent>(
            MeshEvent.Send(
                NearbyPacket.HandshakeAuth(
                    MeshCrypto.toHex(
                        MeshCrypto.handshakeMac(token, localNonce(), nonce, localPeerId),
                    ),
                ),
            ),
        )
        pendingAuth?.let { queued ->
            pendingAuth = null
            events += onAuth(queued)
        }
        return events
    }

    private fun onAuth(packet: NearbyPacket.HandshakeAuth): List<MeshEvent> {
        val remote = remoteNonce
        val peerId = remotePeerId
        if (remote == null || peerId == null) {
            pendingAuth = packet
            return emptyList()
        }
        if (isEstablished) return emptyList()

        val mac = MeshCrypto.fromHex(packet.macHex) ?: return reject()
        if (!MeshCrypto.verifyHandshakeMac(token, localNonce(), remote, peerId, mac)) {
            return reject()
        }

        isEstablished = true
        val events = mutableListOf<MeshEvent>(MeshEvent.Established)
        pendingPackets.forEach { events += MeshEvent.Packet(it) }
        pendingPackets.clear()
        return events
    }

    private fun onSealed(payload: ByteArray): List<MeshEvent> {
        val key = sessionKey ?: return emptyList()
        val ivHex = MeshCrypto.toHex(payload.copyOfRange(0, minOf(payload.size, MeshCrypto.IV_SIZE)))
        if (!seenIvHex.add(ivHex)) return emptyList()
        if (seenIvHex.size > MAX_SEEN_IVS) seenIvHex.remove(seenIvHex.first())

        val plaintext = MeshCrypto.decryptAesGcm(key, payload) ?: return emptyList()
        val packet = NearbyProtocol.decode(plaintext) ?: return emptyList()
        if (packet is NearbyPacket.HandshakeNonce || packet is NearbyPacket.HandshakeAuth) return emptyList()
        if (!isEstablished) {
            pendingPackets += packet
            return emptyList()
        }
        return listOf(MeshEvent.Packet(packet))
    }

    private fun reject(): List<MeshEvent> {
        isRejected = true
        pendingPackets.clear()
        sessionKey = null
        return listOf(MeshEvent.Rejected)
    }

    private fun ensureLocalNonce() {
        if (localNonce == null) noncePacket()
    }

    private fun localNonce(): ByteArray = requireNotNull(localNonce)

    private companion object {
        const val MAX_SEEN_IVS = 10_000
    }
}
