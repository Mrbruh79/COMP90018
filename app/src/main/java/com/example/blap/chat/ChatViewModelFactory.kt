package com.example.blap.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.blap.event.EventServices
import com.example.blap.event.EventViewModel
import com.example.blap.auth.AuthRepository
import com.example.blap.auth.AccountProfileRepository
import com.example.blap.auth.AuthViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A fresh set of closeable resources for each ViewModel, never an application singleton. */
data class ChatDependencies(
    val nearbyTransport: NearbyTransport,
    val chatStore: ChatStore,
    val identityStore: IdentityStore,
    val accountId: String = "",
    val cloudChatController: CloudChatController? = null,
    val privateProfileStore: PrivateProfileStore? = null,
    val notifier: ChatNotifier = NoopChatNotifier,
    val events: EventServices? = null,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val authRepository: AuthRepository? = null,
    val accountProfileRepository: AccountProfileRepository? = null,
    val identityFor: ((String) -> IdentityStore)? = null,
)

class ChatViewModelFactory(
    private val createDependencies: () -> ChatDependencies,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        val viewModel = ChatViewModel(MessagingSession(createDependencies()), ownsSession = true)
        return modelClass.cast(viewModel)!!
    }
}

class MessagingSessionFactory(private val createDependencies: () -> ChatDependencies) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == MessagingSessionOwner::class.java) { "Unsupported ViewModel: ${modelClass.name}" }
        return modelClass.cast(MessagingSessionOwner(MessagingSession(createDependencies())))!!
    }
}

/** Each feature gets a real ViewModel from the same account-scoped session owner. */
class MessagingViewModelFactory(private val session: MessagingSession) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val model = when (modelClass) {
            ChatViewModel::class.java -> ChatViewModel(session)
            ContactsViewModel::class.java -> ContactsViewModel(session)
            GroupsViewModel::class.java -> GroupsViewModel(session)
            ProfileViewModel::class.java -> ProfileViewModel(session)
            AuthViewModel::class.java -> AuthViewModel(session)
            EventViewModel::class.java -> EventViewModel(session)
            else -> throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
        }
        return modelClass.cast(model)!!
    }
}
