package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshSessionTest {
    @Test
    fun matchingTokensCompleteHandshakeAndEncryptMessages() {
        val alice = MeshSession("secret-token", "alice", scriptedRandom(nonce(1), iv(1)))
        val bob = MeshSession("secret-token", "bob", scriptedRandom(nonce(2), iv(2)))

        completeHandshake(alice, bob)

        val hello = NearbyPacket.Hello("alice", "Alice", "hash")
        val wire = alice.encode(hello)
        assertTrue(NearbyProtocol.decode(wire) == null)

        val events = bob.receive(wire)
        assertEquals(listOf(hello), events.filterIsInstance<MeshEvent.Packet>().map { it.packet })
    }

    @Test
    fun mismatchedTokensRejectTheLink() {
        val alice = MeshSession("token-a", "alice", scriptedRandom(nonce(1)))
        val bob = MeshSession("token-b", "bob", scriptedRandom(nonce(2)))

        receiveNonce(alice, bob)
        val bobAuth = receiveNonce(bob, alice)
        val events = alice.receive(bob.encode(bobAuth))

        assertTrue(events.contains(MeshEvent.Rejected))
        assertTrue(alice.isRejected)
    }

    @Test
    fun plaintextApplicationPacketsAreIgnored() {
        val alice = MeshSession("secret-token", "alice", scriptedRandom(nonce(1)))
        val bob = MeshSession("secret-token", "bob", scriptedRandom(nonce(2), iv(2)))
        completeHandshake(alice, bob)

        val events = alice.receive(
            NearbyProtocol.encode(NearbyPacket.Hello("bob", "Bob", "hash")),
        )

        assertTrue(events.none { it is MeshEvent.Packet })
    }

    @Test
    fun tamperedCiphertextIsDropped() {
        val alice = MeshSession("secret-token", "alice", scriptedRandom(nonce(1), iv(1)))
        val bob = MeshSession("secret-token", "bob", scriptedRandom(nonce(2)))
        completeHandshake(alice, bob)

        val wire = alice.encode(NearbyPacket.Hello("alice", "Alice", "hash")).clone()
        wire[wire.lastIndex] = (wire.lastIndex xor 0xff).toByte()

        assertTrue(bob.receive(wire).none { it is MeshEvent.Packet })
    }

    @Test
    fun applicationPacketsWaitUntilTheHandshakeCompletes() {
        val alice = MeshSession("secret-token", "alice", scriptedRandom(nonce(1)))
        val bob = MeshSession("secret-token", "bob", scriptedRandom(nonce(2), iv(2)))

        val bobAuth = receiveNonce(bob, alice)
        val aliceAuth = receiveNonce(alice, bob)
        assertTrue(bob.receive(alice.encode(aliceAuth)).contains(MeshEvent.Established))

        val hello = NearbyPacket.Hello("bob", "Bob", "hash")
        assertTrue(alice.receive(bob.encode(hello)).none { it is MeshEvent.Packet })

        val events = alice.receive(bob.encode(bobAuth))
        assertTrue(events.contains(MeshEvent.Established))
        assertEquals(listOf(hello), events.filterIsInstance<MeshEvent.Packet>().map { it.packet })
    }

    private fun completeHandshake(alice: MeshSession, bob: MeshSession) {
        val bobAuth = receiveNonce(bob, alice)
        val aliceAuth = receiveNonce(alice, bob)
        assertTrue(alice.receive(bob.encode(bobAuth)).contains(MeshEvent.Established))
        assertTrue(bob.receive(alice.encode(aliceAuth)).contains(MeshEvent.Established))
    }

    private fun receiveNonce(receiver: MeshSession, sender: MeshSession): NearbyPacket.HandshakeAuth {
        val events = receiver.receive(sender.encode(sender.noncePacket()))
        return events.filterIsInstance<MeshEvent.Send>()
            .map { it.packet }
            .filterIsInstance<NearbyPacket.HandshakeAuth>()
            .single()
    }

    private fun nonce(seed: Int) = ByteArray(32) { seed.toByte() }
    private fun iv(seed: Int) = ByteArray(12) { seed.toByte() }

    private fun scriptedRandom(vararg chunks: ByteArray): (Int) -> ByteArray {
        val queue = ArrayDeque(chunks.toList())
        return { size ->
            val next = queue.removeFirst()
            require(next.size == size) { "expected $size random bytes, was ${next.size}" }
            next.copyOf()
        }
    }
}
