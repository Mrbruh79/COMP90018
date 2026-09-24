package com.example.blap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionFlowTest {
    @Test
    fun denialPreservesStartupFailureWithoutShowingNotificationsOffNotice() {
        var startRequested = false
        var visibleResult: String? = null

        handleNotificationPermissionResult(
            granted = false,
            startNearby = {
                startRequested = true
                visibleResult = "Nearby failed to start."
                false
            },
            showNotificationsOffNotice = { visibleResult = "Notifications are off." },
        )

        assertTrue(startRequested)
        assertEquals("Nearby failed to start.", visibleResult)
    }

    @Test
    fun denialShowsNotificationsOffNoticeAfterSuccessfulStartRequest() {
        var noticeShown = false

        handleNotificationPermissionResult(
            granted = false,
            startNearby = { true },
            showNotificationsOffNotice = { noticeShown = true },
        )

        assertTrue(noticeShown)
    }

    @Test
    fun grantedPermissionStartsNearbyWithoutShowingNotice() {
        var startRequested = false
        var noticeShown = false

        handleNotificationPermissionResult(
            granted = true,
            startNearby = {
                startRequested = true
                true
            },
            showNotificationsOffNotice = { noticeShown = true },
        )

        assertTrue(startRequested)
        assertFalse(noticeShown)
    }
}
