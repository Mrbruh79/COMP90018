package com.example.blap.chat

import org.junit.Assert.*
import org.junit.Test

class NearbyStartRetryTest {
    @Test fun aTransientStartCanRetryWithABoundedDelay() {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val retry = NearbyStartRetry { delay, action -> pending += delay to action }
        val ticket = retry.begin()
        var calls = 0
        assertTrue(retry.retry(ticket, 0) { calls++ })
        assertEquals(250L, pending.single().first)
        pending.single().second()
        assertEquals(1, calls)
        assertFalse(retry.retry(ticket, 3) { calls++ })
    }

    @Test fun stoppingCancelsQueuedRetries() {
        val pending = mutableListOf<() -> Unit>()
        val retry = NearbyStartRetry { _, action -> pending += action }
        val ticket = retry.begin()
        var calls = 0
        retry.retry(ticket, 0) { calls++ }
        retry.cancel()
        pending.single()()
        assertEquals(0, calls)
        assertFalse(retry.retry(ticket, 1) { calls++ })
    }

    @Test fun aNewStartSupersedesTheOldCallbacksAndRetries() {
        val pending = mutableListOf<() -> Unit>()
        val retry = NearbyStartRetry { _, action -> pending += action }
        val old = retry.begin()
        var calls = 0
        retry.retry(old, 0) { calls++ }
        val current = retry.begin()
        pending.single()()
        assertFalse(retry.isCurrent(old))
        assertTrue(retry.isCurrent(current))
        assertEquals(0, calls)
    }
}
