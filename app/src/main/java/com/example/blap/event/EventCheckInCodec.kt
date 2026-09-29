package com.example.blap.event

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

data class EventCheckInCredential(
    val eventId: String,
    val adminId: String,
    val nonce: String,
)

/** Creates static, admin-signed venue check-in QR payloads for public events. */
object EventCheckInCodec {
    private const val PREFIX = "COMMONGROUND-CHECKIN:2:"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun generateAdminKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair()

    fun encodePublicKey(publicKey: PublicKey): String = encoder.encodeToString(publicKey.encoded)

    fun decodePublicKey(encoded: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(
        X509EncodedKeySpec(decoder.decode(encoded)),
    )

    fun encodePrivateKey(privateKey: PrivateKey): String = encoder.encodeToString(privateKey.encoded)

    fun decodePrivateKey(encoded: String): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(
        PKCS8EncodedKeySpec(decoder.decode(encoded)),
    )

    fun create(
        eventId: String,
        adminId: String,
        privateKey: PrivateKey,
        nonce: String = UUID.randomUUID().toString(),
    ): String {
        require(eventId.isNotBlank())
        require(adminId.isNotBlank())
        require(nonce.isNotBlank())
        val credential = EventCheckInCredential(
            eventId = eventId,
            adminId = adminId,
            nonce = nonce,
        )
        val data = encodeCredential(credential)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return PREFIX + encoder.encodeToString(data) + "." + encoder.encodeToString(signature)
    }

    fun verify(
        payload: String,
        expectedEventId: String,
        publicKey: PublicKey,
    ): EventCheckInCredential? = runCatching {
        if (!payload.startsWith(PREFIX)) return null
        val parts = payload.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return null
        val data = decoder.decode(parts[0])
        val signatureBytes = decoder.decode(parts[1])
        val validSignature = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(signatureBytes)
        }
        if (!validSignature) return null
        val credential = decodeCredential(data) ?: return null
        if (credential.eventId != expectedEventId) return null
        if (credential.adminId.isBlank() || credential.nonce.isBlank()) return null
        credential
    }.getOrNull()

    private fun encodeCredential(credential: EventCheckInCredential): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeUTF(credential.eventId)
            output.writeUTF(credential.adminId)
            output.writeUTF(credential.nonce)
        }
        return bytes.toByteArray()
    }

    private fun decodeCredential(bytes: ByteArray): EventCheckInCredential? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            EventCheckInCredential(
                eventId = input.readUTF().take(128),
                adminId = input.readUTF().take(128),
                nonce = input.readUTF().take(128),
            ).takeIf { input.available() == 0 }
        }
    }.getOrNull()
}
