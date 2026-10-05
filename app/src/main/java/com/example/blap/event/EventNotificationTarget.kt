package com.example.blap.event

/** Shared notification contract; contains no Android navigation or notification APIs. */
data class EventNotificationTarget(val eventId: String, val accountId: String? = null) {
    fun belongsTo(currentAccountId: String): Boolean =
        accountId == null || accountId == currentAccountId

    fun isAvailable(state: EventUiState): Boolean = state.events.any {
        it.id == eventId && !it.isDeleted && state.currentUserId in it.memberIds
    }

    companion object {
        const val EXTRA_EVENT_ID = "notification_event_id"
        const val EXTRA_ACCOUNT_ID = "notification_account_id"
    }
}
