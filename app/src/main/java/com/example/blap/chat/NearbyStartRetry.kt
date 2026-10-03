package com.example.blap.chat

/** Bounded radio-start retries; stopped or superseded attempts cannot restart Nearby. */
internal class NearbyStartRetry(private val schedule: (Long, () -> Unit) -> Unit) {
    @Volatile private var generation = 0L

    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun cancel() { generation++ }
    fun isCurrent(ticket: Long): Boolean = ticket == generation

    fun retry(ticket: Long, attempt: Int, start: () -> Unit): Boolean {
        if (!isCurrent(ticket) || attempt !in DELAYS.indices) return false
        schedule(DELAYS[attempt]) { if (isCurrent(ticket)) start() }
        return true
    }

    private companion object { val DELAYS = listOf(250L, 500L, 1_000L) }
}
