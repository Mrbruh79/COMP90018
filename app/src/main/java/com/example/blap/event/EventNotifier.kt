package com.example.blap.event

// Keeps event notification handling separate from event state and transport logic.
interface EventNotifier {

    fun incomingAnnouncement(
        event: CommunityEvent,
        announcement: EventAnnouncement,
        announcementsVisible: Boolean,
    )

    fun incomingChatMessage(
        event: CommunityEvent,
        message: EventChatMessage,
        chatVisible: Boolean,
    )
}

// Used when event notifications are not configured, such as in tests.
object NoopEventNotifier : EventNotifier {

    override fun incomingAnnouncement(
        event: CommunityEvent,
        announcement: EventAnnouncement,
        announcementsVisible: Boolean,
    ) = Unit

    override fun incomingChatMessage(
        event: CommunityEvent,
        message: EventChatMessage,
        chatVisible: Boolean,
    ) = Unit
}