package com.example.blap.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCryptoTest {
    @Test
    fun hmacSha256MatchesRfc4231Case1() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".toByteArray(Charsets.UTF_8)

        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            MeshCrypto.toHex(MeshCrypto.hmacSha256(key, data)),
        )
    }

    @Test
    fun sessionKeyIsTheSameRegardlessOfNonceOrder() {
        val token = "shared-mesh-token"
        val nonceA = ByteArray(32) { it.toByte() }
        val nonceB = ByteArray(32) { (32 - it).toByte() }

        assertArrayEquals(
            MeshCrypto.sessionKey(token, nonceA, nonceB),
            MeshCrypto.sessionKey(token, nonceB, nonceA),
        )
    }

    @Test
    fun handshakeMacFailsWhenTheTokenDoesNotMatch() {
        val localNonce = ByteArray(32) { 1 }
        val remoteNonce = ByteArray(32) { 2 }
        val mac = MeshCrypto.handshakeMac("correct-token", localNonce, remoteNonce, "alice")

        assertFalse(
            MeshCrypto.verifyHandshakeMac("wrong-token", remoteNonce, localNonce, "alice", mac),
        )
        assertTrue(
            MeshCrypto.verifyHandshakeMac("correct-token", remoteNonce, localNonce, "alice", mac),
        )
    }

    @Test
    fun aesGcmRoundTripAndRejectsTampering() {
        val key = MeshCrypto.sessionKey("token", ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val iv = ByteArray(12) { 9 }
        val sealed = MeshCrypto.encryptAesGcm(key, "hello mesh".toByteArray(Charsets.UTF_8), iv)

        assertEquals("hello mesh", MeshCrypto.decryptAesGcm(key, sealed)?.toString(Charsets.UTF_8))

        sealed[sealed.lastIndex] = (sealed.lastIndex.toByte())
        assertNull(MeshCrypto.decryptAesGcm(key, sealed))
    }

    @Test
    fun blankTokenFallsBackToTheDefaultMeshToken() {
        assertNotNull(MeshCrypto.sessionKey("", ByteArray(32), ByteArray(32) { 1 }))
        assertArrayEquals(
            MeshCrypto.sessionKey(MeshCrypto.DEFAULT_TOKEN, ByteArray(32), ByteArray(32) { 1 }),
            MeshCrypto.sessionKey("   ", ByteArray(32), ByteArray(32) { 1 }),
        )
    }
}
