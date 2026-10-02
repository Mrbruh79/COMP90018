package com.example.blap.application

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.example.blap.auth.AuthViewModel
import com.example.blap.chat.ChatViewModel
import com.example.blap.chat.ContactsViewModel
import com.example.blap.chat.GroupsViewModel
import com.example.blap.chat.MessagingSessionOwner
import com.example.blap.chat.ProfileViewModel
import com.example.blap.di.AppContainer
import com.example.blap.event.EventViewModel

/** One retained account graph. Activity recreation reuses all seven ViewModels. */
data class ApplicationViewModels(
    val application: ApplicationViewModel,
    val chat: ChatViewModel,
    val contacts: ContactsViewModel,
    val groups: GroupsViewModel,
    val profile: ProfileViewModel,
    val auth: AuthViewModel,
    val events: EventViewModel,
) {
    companion object {
        fun obtain(owner: ViewModelStoreOwner, container: AppContainer): ApplicationViewModels {
            val root = ViewModelProvider(owner,
                container.messagingSessionFactory(container.authentication.account.uid))[MessagingSessionOwner::class.java]
            val features = ViewModelProvider(owner, container.featureViewModelFactory(root.session))
            val chat = features[ChatViewModel::class.java]
            val contacts = features[ContactsViewModel::class.java]
            val groups = features[GroupsViewModel::class.java]
            val profile = features[ProfileViewModel::class.java]
            val auth = features[AuthViewModel::class.java]
            val events = features[EventViewModel::class.java]
            val commands = ApplicationCommands(
                startNearby = chat::startChat,
                importContacts = contacts::importDeviceContacts,
                enterEventWithGps = { events.enterEventWithGps(it.latitude, it.longitude, it.accuracyMetres) },
                enterEventWithQr = events::enterEventWithQr,
                importContactQr = contacts::importScannedContactCard,
                ensureSignedIn = auth::ensureSignedIn,
                refreshAccount = auth::refreshAccount,
                signInWithGoogleToken = auth::signInWithGoogleToken,
                showError = chat::showError,
                showNotice = chat::showNotice,
                venueStatus = { message, checking -> chat.updateVenueStatus(message, checking) },
                chatScreen = { chat.uiState.value.screen },
                eventPage = { events.uiState.value.page },
                selectedEventId = { events.uiState.value.selectedEventId },
                chatBack = root.session.navigation::handleBack,
                eventBack = events::eventBack,
                showChats = chat::showConversationList,
            )
            val application = ViewModelProvider(owner, ApplicationViewModelFactory {
                ApplicationViewModel(container.applicationServicesFor(root.session.dependencies.accountId), commands,
                    root.session.dependencies.ioDispatcher)
            })[ApplicationViewModel::class.java]
            root.session.closeApplication = application::freezeForAccountChange
            return ApplicationViewModels(application, chat, contacts, groups, profile, auth, events)
        }
    }
}

class ApplicationViewModelFactory(private val create: () -> ApplicationViewModel) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ApplicationViewModel::class.java) { "Unsupported ViewModel: ${modelClass.name}" }
        return modelClass.cast(create())!!
    }
}
