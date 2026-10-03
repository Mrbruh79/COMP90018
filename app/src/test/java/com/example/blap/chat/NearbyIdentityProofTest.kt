package com.example.blap.chat

import org.junit.Assert.*
import org.junit.Test

class NearbyIdentityProofTest {
    private val keys = NearbyIdentityProof.generateKeys()
    private val token = ByteArray(32) { it.toByte() }
    private fun signed(): NearbyPacket.Hello {
        val hello = NearbyPacket.Hello("bob-peer", "Bob", "", "bob", "bob-uid", NearbyIdentityProof.publicKey(keys))
        return hello.copy(signature = NearbyIdentityProof.sign(hello, token, keys))
    }
    @Test fun proofIsBoundToTheConnectionAndAllIdentityFields() {
        val hello = signed()
        assertTrue(NearbyIdentityProof.verify(hello, token))
        assertFalse(NearbyIdentityProof.verify(hello, ByteArray(32) { 42 }))
        assertFalse(NearbyIdentityProof.verify(hello.copy(username = "alice"), token))
        assertFalse(NearbyIdentityProof.verify(hello.copy(accountUid = "alice-uid"), token))
        assertFalse(NearbyIdentityProof.verify(hello.copy(peerId = "other-device"), token))
        assertFalse(NearbyIdentityProof.verify(hello.copy(name = "Alice"), token))
        assertFalse(NearbyIdentityProof.verify(hello.copy(publicKey = "bad"), token))
        assertFalse(NearbyIdentityProof.verify(hello, byteArrayOf()))
    }
    @Test fun helloExchangesUsernameWithoutBreakingLegacyHelloPackets() {
        val hello = signed()
        assertEquals(hello, NearbyProtocol.decode(NearbyProtocol.encode(hello)))
        val legacy = NearbyPacket.Hello("legacy", "Legacy", "")
        assertEquals(legacy, NearbyProtocol.decode(NearbyProtocol.encode(legacy)))
    }
    @Test fun aSavedUsernameAloneCannotMatchADeviceKey() {
        val hello = signed()
        val trusted = TrustedNearbyIdentity(hello.peerId, hello.username, hello.accountUid, hello.publicKey, true)
        assertTrue(NearbyIdentityProof.matches(hello, trusted))
        assertFalse(NearbyIdentityProof.matches(hello.copy(publicKey = NearbyIdentityProof.publicKey(NearbyIdentityProof.generateKeys())), trusted))
    }
    @Test fun advertisementsExposeIdentityHintsWithoutTreatingThemAsProof() {
        val name = NearbyEndpointIdentity.name("bob-peer", "bob", "Bob | Smith")
        assertEquals(NearbyDevice("endpoint", "Bob   Smith", "bob-peer", "bob"), NearbyEndpointIdentity.device("endpoint", name))
        assertEquals(NearbyDevice("endpoint", "Old phone"), NearbyEndpointIdentity.device("endpoint", "Old phone"))
    }
}
