package com.example.blap.chat

import android.content.Context
import androidx.core.content.edit
import com.example.blap.security.EncryptedPreferences
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONObject

data class TrustedNearbyIdentity(val peerId: String, val username: String, val accountUid: String,
    val publicKey: String, val accountVerified: Boolean = false)

interface NearbyIdentityStore {
    fun keys(): KeyPair
    fun trusted(peerId: String): TrustedNearbyIdentity?
    fun remember(identity: TrustedNearbyIdentity)
}

class InMemoryNearbyIdentityStore : NearbyIdentityStore {
    private val pair by lazy { NearbyIdentityProof.generateKeys() }
    private val peers = java.util.concurrent.ConcurrentHashMap<String, TrustedNearbyIdentity>()
    override fun keys() = pair
    override fun trusted(peerId: String) = peers[peerId]
    override fun remember(identity: TrustedNearbyIdentity) { peers[identity.peerId] = identity }
}

/** Keys and remembered devices belong to the signed-in account's local storage scope. */
class LocalNearbyIdentityStore(context: Context, scope: String) : NearbyIdentityStore {
    private val preferences = EncryptedPreferences.open(context, "nearby_identity$scope")
    @Synchronized override fun keys(): KeyPair {
        val privateKey = preferences.getString("private", null)
        val publicKey = preferences.getString("public", null)
        if (privateKey != null && publicKey != null) {
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))))
        }
        return NearbyIdentityProof.generateKeys().also { pair ->
            preferences.edit {
                putString("private", Base64.getEncoder().encodeToString(pair.private.encoded))
                putString("public", NearbyIdentityProof.publicKey(pair))
            }
        }
    }
    override fun trusted(peerId: String): TrustedNearbyIdentity? = runCatching {
        val json = JSONObject(preferences.getString("peer:$peerId", null) ?: return null)
        TrustedNearbyIdentity(peerId, json.getString("username"), json.getString("uid"),
            json.getString("key"), json.optBoolean("verified"))
    }.getOrNull()
    override fun remember(identity: TrustedNearbyIdentity) {
        val json = JSONObject().put("username", identity.username).put("uid", identity.accountUid)
            .put("key", identity.publicKey).put("verified", identity.accountVerified)
        preferences.edit { putString("peer:${identity.peerId}", json.toString()) }
    }
}

internal object NearbyIdentityProof {
    fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    fun publicKey(keys: KeyPair): String = Base64.getEncoder().encodeToString(keys.public.encoded)
    fun sign(hello: NearbyPacket.Hello, token: ByteArray, keys: KeyPair): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(payload(hello, token)); sign()
        })
    fun verify(hello: NearbyPacket.Hello, token: ByteArray): Boolean = runCatching {
        if (token.isEmpty() || hello.publicKey.length !in 80..256 || hello.signature.length !in 80..256 ||
            hello.peerId.length !in 1..100 || hello.accountUid.length !in 1..128 ||
            !hello.username.matches(Regex("[a-z0-9_]{3,20}"))) return false
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(hello.publicKey)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(payload(hello, token)); verify(Base64.getDecoder().decode(hello.signature))
        }
    }.getOrDefault(false)
    private fun payload(hello: NearbyPacket.Hello, token: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("BLAP-NEARBY-IDENTITY:1")
            listOf(hello.peerId, hello.name, hello.phoneHash, hello.username, hello.accountUid, hello.publicKey).forEach(out::writeUTF)
            out.writeInt(token.size); out.write(token)
        }
    }.toByteArray()
    fun matches(hello: NearbyPacket.Hello, trusted: TrustedNearbyIdentity): Boolean =
        hello.peerId == trusted.peerId && hello.publicKey == trusted.publicKey &&
            hello.username == trusted.username && hello.accountUid == trusted.accountUid
}

internal object NearbyEndpointIdentity {
    fun name(peerId: String, username: String, displayName: String): String =
        "blap|$peerId|$username|${displayName.replace('|', ' ').take(24)}"
    fun device(endpointId: String, name: String): NearbyDevice {
        val parts = name.split('|', limit = 4)
        return if (parts.size == 4 && parts[0] == "blap" && parts[1].length in 1..100 &&
            parts[2].matches(Regex("[a-z0-9_]{3,20}")))
            NearbyDevice(endpointId, parts[3].take(24), parts[1], parts[2]) else NearbyDevice(endpointId, name.take(100))
    }
}
