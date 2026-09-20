package com.example.blap.chat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object MeshCrypto {
    const val DEFAULT_TOKEN = "blap-mesh"
    const val NONCE_SIZE = 32
    const val IV_SIZE = 12
    const val GCM_TAG_BITS = 128

    private val AAD = "BLAP-GCM-v1".toByteArray(Charsets.UTF_8)
    private val SESSION_INFO = "blap-aes-gcm-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun tokenBytes(token: String): ByteArray {
        val trimmed = token.trim()
        return (trimmed.ifEmpty { DEFAULT_TOKEN }).toByteArray(Charsets.UTF_8)
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun handshakeMac(
        token: String,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        localPeerId: String,
    ): ByteArray = hmacSha256(
        tokenBytes(token),
        localNonce + remoteNonce + localPeerId.toByteArray(Charsets.UTF_8),
    )

    fun verifyHandshakeMac(
        token: String,
        localNonce: ByteArray,
        remoteNonce: ByteArray,
        remotePeerId: String,
        mac: ByteArray,
    ): Boolean {
        val expected = handshakeMac(token, remoteNonce, localNonce, remotePeerId)
        return mac.size == expected.size && MessageDigest.isEqual(expected, mac)
    }

    fun sessionKey(token: String, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        val (first, second) = if (compareUnsigned(nonceA, nonceB) <= 0) nonceA to nonceB else nonceB to nonceA
        return hkdfSha256(tokenBytes(token), first + second, SESSION_INFO, 32)
    }

    fun encryptAesGcm(key: ByteArray, plaintext: ByteArray, iv: ByteArray = randomBytes(IV_SIZE)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(plaintext)
    }

    fun decryptAesGcm(key: ByteArray, ivAndCiphertext: ByteArray): ByteArray? {
        if (ivAndCiphertext.size <= IV_SIZE + GCM_TAG_BITS / 8) return null
        return try {
            val iv = ivAndCiphertext.copyOfRange(0, IV_SIZE)
            val sealed = ivAndCiphertext.copyOfRange(IV_SIZE, ivAndCiphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(sealed)
        } catch (_: Exception) {
            null
        }
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val result = ArrayList<Byte>(length)
        var previous = ByteArray(0)
        var block = 1
        while (result.size < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(block.toByte()))
            previous.forEach { result += it }
            block++
        }
        return result.take(length).toByteArray()
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val delta = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (delta != 0) return delta
        }
        return left.size - right.size
    }
}
