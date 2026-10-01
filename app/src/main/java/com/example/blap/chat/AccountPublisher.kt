package com.example.blap.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

class AccountPublisher(private val session: MessagingSession) {

    fun applyAccountProfile(username: String, displayName: String) {
        val updated = session.identityStore.getProfile().copy(username = username, displayName = displayName)
        session.identityStore.saveProfileAt(updated, session.identityStore.profileUpdatedAt())
        session.state.update { state ->
            state.copy(
                displayName = displayName,
                screen = if (state.screen == ChatScreen.WELCOME) ChatScreen.CHATS else state.screen,
            )
        }
        session.workScope.launch {
            publishAccount()
            session.cloudSync.syncPending()
        }
    }

    suspend fun publishAccount(reportSuccess: Boolean = false) {
        if (session.state.value.onlineAccountId.isBlank() || session.state.value.displayName.isBlank() ||
            session.identityStore.getProfile().username.isBlank()) {
            if (reportSuccess) session.showCloudError("Finish account setup before enabling phone lookup.")
            return
        }
        val controller = session.cloudController ?: return
        runCatching { controller.publishAccount(session.currentProfile(), session.localPeerId) }
            .onSuccess {
                session.state.update { state -> state.copy(onlineLookupStatus =
                    if (state.profileDiscoverableByPhone) "Phone lookup is active"
                    else "Phone lookup is off") }
                if (reportSuccess) session.showNotice(if (session.state.value.profileDiscoverableByPhone)
                    "Phone lookup is active for your saved discovery number."
                    else "Phone lookup is off.")
            }
            .onFailure {
                session.state.update { state -> state.copy(onlineLookupStatus = "Online update failed") }
                session.showCloudError(it.localizedMessage ?: "Could not publish your account details.")
            }
    }

}
