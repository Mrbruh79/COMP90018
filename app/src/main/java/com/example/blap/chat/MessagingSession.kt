package com.example.blap.chat

import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One account's resources and shared rendering snapshot, retained by the Activity's ViewModelStore. */
class MessagingSession(val dependencies: ChatDependencies) : AutoCloseable {
    val store = dependencies.chatStore
    val identityStore = dependencies.identityStore
    val nearbyTransport = dependencies.nearbyTransport
    val cloudController = dependencies.cloudChatController
    val privateProfileStore = dependencies.privateProfileStore
    val workScope = CoroutineScope(SupervisorJob() + dependencies.ioDispatcher)
    val initialProfile = identityStore.getProfile()
    val localPeerId = identityStore.getPeerId()
    var localPhoneHash = PhoneIdentity.hash(initialProfile.phoneNumber).orEmpty()
    val connectedPeers = ConcurrentHashMap<String, ConnectedPeer>()
    var requestedEndpointId: String? = null
    var profileReturnScreen = ChatScreen.SHOWING_MY_CARD
    var contactReturnScreen = ChatScreen.MANAGING_CONTACTS
    var groupReturnScreen = ChatScreen.CHATS
    private val closed = AtomicBoolean(false)
    internal var closeEvents: (() -> Unit)? = null
    val state = MutableStateFlow(
        ChatUiState(
            myPeerId = localPeerId,
            screen = if (initialProfile.displayName.isNotBlank() &&
                (initialProfile.phoneNumber.isBlank() || PhoneIdentity.normalize(initialProfile.phoneNumber) != null)
            ) ChatScreen.CHATS else ChatScreen.WELCOME,
            displayName = initialProfile.displayName,
            phoneNumber = initialProfile.phoneNumber,
            profileEmail = initialProfile.email,
            profileGoogleEmail = initialProfile.googleAccountEmail,
            profileDiscoverableByPhone = initialProfile.discoverableByPhone,
            profileLookupPhoneNumber = initialProfile.lookupPhoneNumber,
            profileBio = initialProfile.bio,
            profileWebsite = initialProfile.websiteUrl,
            profileInstagram = initialProfile.instagramUrl,
            profileX = initialProfile.xUrl,
            profileLinkedin = initialProfile.linkedinUrl,
            profileGithub = initialProfile.githubUrl,
            onlineAccountId = dependencies.accountId,
        ),
    )
    val uiState: StateFlow<ChatUiState> = state.asStateFlow()


    val persistence by lazy { LocalChatPersistence(this) }
    val accountLookup by lazy { ContactAccountLookup(this) }
    val cloudSync by lazy { CloudChatSynchronizer(this) }
    val profileBackup by lazy { ProfileBackup(this) }
    val accountPublisher by lazy { AccountPublisher(this) }
    val identityService by lazy { ProfileIdentity(this) }
    val groupManagement by lazy { GroupManagement(this) }
    val navigation by lazy { MessagingNavigation(this) }

    init {
        workScope.launch {
            store.savePeer(MeshGroup.ID, MeshGroup.NAME)
            persistence.reloadConversationsNow()
            persistence.reloadSavedContactsNow()
            cloudSync.start()
        }
    }

    fun showError(message: String) { state.update { it.copy(error = message) } }
    fun showNotice(message: String) { state.update { it.copy(notice = message) } }
    fun showCloudError(message: String) = showNotice("Online chat: $message")

    fun currentProfile(): ContactProfile = state.value.let {
        ContactProfile(
            displayName = it.displayName,
            phoneNumber = it.phoneNumber,
            email = it.profileEmail,
            googleAccountEmail = it.profileGoogleEmail,
            discoverableByPhone = it.profileDiscoverableByPhone,
            lookupPhoneNumber = it.profileLookupPhoneNumber,
            bio = it.profileBio,
            websiteUrl = it.profileWebsite,
            instagramUrl = it.profileInstagram,
            xUrl = it.profileX,
            linkedinUrl = it.profileLinkedin,
            githubUrl = it.profileGithub,
            username = identityStore.getProfile().username,
        )
    }

    fun applyProfile(profile: ContactProfile) {
        state.update {
            it.copy(
                displayName = profile.displayName.take(ChatLimits.MAX_NAME_LENGTH),
                phoneNumber = profile.phoneNumber.take(ChatLimits.MAX_PHONE_LENGTH),
                profileEmail = profile.email.take(ChatLimits.MAX_EMAIL_LENGTH),
                profileGoogleEmail = profile.googleAccountEmail.take(ChatLimits.MAX_EMAIL_LENGTH),
                profileDiscoverableByPhone = profile.discoverableByPhone,
                profileLookupPhoneNumber = profile.lookupPhoneNumber,
                profileBio = profile.bio.take(ChatLimits.MAX_BIO_LENGTH),
                profileWebsite = profile.websiteUrl.take(ChatLimits.MAX_URL_LENGTH),
                profileInstagram = profile.instagramUrl.take(ChatLimits.MAX_URL_LENGTH),
                profileX = profile.xUrl.take(ChatLimits.MAX_URL_LENGTH),
                profileLinkedin = profile.linkedinUrl.take(ChatLimits.MAX_URL_LENGTH),
                profileGithub = profile.githubUrl.take(ChatLimits.MAX_URL_LENGTH),
            )
        }
    }


    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workScope.cancel()
        cloudController?.stop()
        closeEvents?.invoke() ?: dependencies.events?.store?.close()
        nearbyTransport.close()
        store.close()
    }
}

class MessagingSessionOwner(val session: MessagingSession) : ViewModel() {
    override fun onCleared() = session.close()
}

sealed interface IdentityCheck {
    data class Valid(val name: String, val phone: String) : IdentityCheck
    data class Invalid(val nameError: String?, val phoneError: String?) : IdentityCheck
}
