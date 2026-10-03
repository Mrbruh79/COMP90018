package com.example.blap.ui.components

internal object ReplySwipe {
    fun offset(current: Float, movement: Float, mine: Boolean, limit: Float): Float =
        if (mine) (current + movement).coerceIn(-limit, 0f)
        else (current + movement).coerceIn(0f, limit)

    fun shouldReply(offset: Float, mine: Boolean, threshold: Float): Boolean =
        if (mine) offset <= -threshold else offset >= threshold
}
