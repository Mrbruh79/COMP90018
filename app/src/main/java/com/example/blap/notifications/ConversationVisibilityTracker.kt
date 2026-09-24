package com.example.blap.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationVisibilityTracker {
    private val _appForeground = MutableStateFlow(false)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    fun setAppForeground(value: Boolean) {
        _appForeground.value = value
    }

    fun isAppForeground(): Boolean = appForeground.value
}
