package com.example.blap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySwipeTest {
    @Test fun ownMessagesMoveLeftAndReplyOnlyAfterTheThreshold() {
        assertEquals(0f, ReplySwipe.offset(0f, 70f, true, 88f), 0f)
        assertEquals(-88f, ReplySwipe.offset(0f, -120f, true, 88f), 0f)
        assertFalse(ReplySwipe.shouldReply(-67f, true, 68f))
        assertFalse(ReplySwipe.shouldReply(80f, true, 68f))
        assertTrue(ReplySwipe.shouldReply(-68f, true, 68f))
    }

    @Test fun receivedMessagesMoveRightAndReplyOnlyAfterTheThreshold() {
        assertEquals(0f, ReplySwipe.offset(0f, -70f, false, 88f), 0f)
        assertEquals(88f, ReplySwipe.offset(0f, 120f, false, 88f), 0f)
        assertFalse(ReplySwipe.shouldReply(67f, false, 68f))
        assertFalse(ReplySwipe.shouldReply(-80f, false, 68f))
        assertTrue(ReplySwipe.shouldReply(68f, false, 68f))
    }

    @Test fun reversingTheGestureCanCancelAReply() {
        val left = ReplySwipe.offset(0f, -70f, true, 88f)
        assertEquals(0f, ReplySwipe.offset(left, 90f, true, 88f), 0f)
        val right = ReplySwipe.offset(0f, 70f, false, 88f)
        assertEquals(0f, ReplySwipe.offset(right, -90f, false, 88f), 0f)
    }
}
