package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CloudChatIdsTest {
    @Test
    fun directIdIsSameForBothParticipants() {
        assertEquals(
            CloudChatIds.direct("+15551234567", "+12025550198"),
            CloudChatIds.direct("+12025550198", "+15551234567"),
        )
    }

    @Test
    fun differentPairsHaveDifferentIds() {
        assertNotEquals(
            CloudChatIds.direct("+15551234567", "+12025550198"),
            CloudChatIds.direct("+15551234567", "+12025550199"),
        )
    }
}
