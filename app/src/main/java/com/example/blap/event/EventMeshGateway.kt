package com.example.blap.event

/** Event traffic on the shared Nearby session. NearbyChatManager implements this via EventMeshSession. */
interface EventMeshGateway {
    fun setActiveEvent(
        eventId: String?,
        meshSecret: String = "",
        userId: String = "",
        accessGranted: Boolean = true,
    ) = Unit
    fun sendEventChatMessage(message: EventChatMessage) = Unit
    fun sendEventAnnouncement(announcement: EventAnnouncement) = Unit
    fun sendEventMutation(mutation: EventMutation) = Unit
    fun sendEventAccessRequest(request: EventAccessRequest) = Unit
    fun sendEventAccessGrant(grant: EventAccessGrant) = Unit
    fun synchronizeEventHistory(peerId: String, messages: List<EventChatMessage>) = Unit
    fun synchronizeEventAnnouncements(peerId: String, announcements: List<EventAnnouncement>) = Unit

    interface Listener {
        fun onEventPeerAvailable(peerId: String, eventId: String, userId: String, accessGranted: Boolean) = Unit
        fun onEventChatMessageReceived(message: EventChatMessage) = Unit
        fun onEventAnnouncementReceived(announcement: EventAnnouncement) = Unit
        fun onEventMutationReceived(mutation: EventMutation) = Unit
        fun onEventAccessRequestReceived(request: EventAccessRequest) = Unit
        fun onEventAccessGrantReceived(grant: EventAccessGrant) = Unit
        fun onEventMessageSent(eventId: String, messageId: String) = Unit
    }
}
