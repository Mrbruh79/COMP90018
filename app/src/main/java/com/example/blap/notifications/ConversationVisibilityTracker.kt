package com.example.blap.notifications

class ConversationVisibilityTracker {
    @Volatile
    private var appForeground = false

    fun setAppForeground(value: Boolean) {
        appForeground = value
    }

    fun isAppForeground(): Boolean = appForeground
}
