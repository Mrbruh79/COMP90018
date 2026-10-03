package com.example.blap.event

/** A venue check-in lasts for this event, not just the on-site chat screen. */
internal object EventCheckInState {
    private val sessionPages = setOf(
        EventPage.DETAIL,
        EventPage.ANNOUNCEMENTS,
        EventPage.ON_SITE_CHAT,
        EventPage.DISCUSSION,
        EventPage.DISCUSSION_THREAD,
    )

    fun isSessionPage(page: EventPage): Boolean = page in sessionPages

    fun isCheckedIn(event: CommunityEvent, member: EventMembership?, now: Long): Boolean =
        member != null && member.eventId == event.id && member.canParticipate &&
            member.userId in event.memberIds &&
            member.checkedInAt != null && member.accessMethod != null && event.isActive(now)

    fun needsGps(state: EventUiState, now: Long): Boolean {
        val event = state.selectedEvent ?: return false
        val member = state.membership ?: return false
        return isSessionPage(state.page) && member.eventId == event.id &&
            member.userId in event.memberIds &&
            member.canParticipate && event.isActive(now) && !isCheckedIn(event, member, now)
    }

    fun mergeMembership(remote: EventMembership, local: EventMembership?): EventMembership =
        if (remote.canParticipate && local?.canParticipate == true &&
            remote.eventId == local.eventId && remote.userId == local.userId && remote.joinedAt == local.joinedAt) {
            remote.copy(accessMethod = local.accessMethod ?: remote.accessMethod,
                checkedInAt = local.checkedInAt ?: remote.checkedInAt)
        } else remote
}
