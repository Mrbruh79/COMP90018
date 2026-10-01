package com.example.blap.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.blap.event.EventServices
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
)

class ChatViewModelFactory(
    private val createDependencies: () -> ChatDependencies,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == ChatViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        val dependencies = createDependencies()
        val viewModel = ChatViewModel(
            nearbyChatController = dependencies.nearbyTransport,
            chatStore = dependencies.chatStore,
            identityStore = dependencies.identityStore,
            ioDispatcher = dependencies.ioDispatcher,
            eventStore = dependencies.events?.store,
            eventRemoteRepository = dependencies.events?.remoteRepository,
            eventAdminKeyStore = dependencies.events?.adminKeyStore,
            eventMeshGateway = dependencies.events?.meshGateway,
            cloudChatController = dependencies.cloudChatController,
            chatNotifier = dependencies.notifier,
            initialAccountId = dependencies.accountId,
            privateProfileStore = dependencies.privateProfileStore,
        )
        return modelClass.cast(viewModel)!!
    }
}
