package com.example.blap.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.example.blap.chat.AndroidChatNotifier
import com.example.blap.chat.ChatDependencies
import com.example.blap.chat.ChatNotificationSettingsStore
import com.example.blap.chat.ChatViewModelFactory
import com.example.blap.chat.MessagingSessionFactory
import com.example.blap.chat.MessagingSession
import com.example.blap.chat.MessagingViewModelFactory
import com.example.blap.application.ApplicationServices
import com.example.blap.platform.AndroidDeviceContactsSource
import com.example.blap.ui.screens.onboarding.OnboardingPreferences
import com.example.blap.auth.AuthRepository
import com.example.blap.auth.FirebaseAuthRepository
import com.example.blap.auth.FirebaseAccountProfileRepository
import com.example.blap.chat.FirebaseCloudChatController
import com.example.blap.chat.FirebasePrivateProfileStore
import com.example.blap.chat.IdentityStore
import com.example.blap.chat.LocalDataScope
import com.example.blap.chat.LocalIdentityStore
import com.example.blap.chat.NearbyChatManager
import com.example.blap.chat.NotificationSettingsRepository
import com.example.blap.chat.PrivateProfileStore
import com.example.blap.chat.SqliteChatStore
import com.example.blap.event.EventServices
import com.example.blap.event.FirebaseEventRemoteRepository
import com.example.blap.event.LocalEventAdminKeyStore
import com.example.blap.event.SqliteEventStore
import com.example.blap.location.FusedLocationProvider
import com.example.blap.location.LocationProvider
import com.example.blap.location.NominatimPlaceSearchRepository
import com.example.blap.location.PlaceSearchRepository
import com.example.blap.venue.FirebaseVenueRepository
import com.example.blap.venue.VenueRepository

interface AppContainer {
    val authentication: AuthRepository
    val locationProvider: LocationProvider
    val venues: VenueRepository
    val placeSearch: PlaceSearchRepository
    val privateProfiles: PrivateProfileStore
    fun identityFor(accountId: String): IdentityStore
    fun notificationSettingsFor(accountId: String): NotificationSettingsRepository
    fun chatViewModelFactory(accountId: String): ViewModelProvider.Factory
    fun messagingSessionFactory(accountId: String): ViewModelProvider.Factory
    fun featureViewModelFactory(session: MessagingSession): ViewModelProvider.Factory = MessagingViewModelFactory(session)
    fun applicationServicesFor(accountId: String): ApplicationServices
}

/** Composition root. Only application context is retained; account resources are not cached. */
class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    override val authentication: AuthRepository by lazy { FirebaseAuthRepository() }

    override val locationProvider: LocationProvider by lazy { FusedLocationProvider(appContext) }
    override val venues: VenueRepository by lazy {
        FirebaseVenueRepository(locationProvider = locationProvider)
    }
    override val placeSearch: PlaceSearchRepository by lazy { NominatimPlaceSearchRepository() }
    override val privateProfiles: PrivateProfileStore by lazy { FirebasePrivateProfileStore() }

    override fun identityFor(accountId: String): IdentityStore =
        LocalIdentityStore(appContext, LocalDataScope.forAccount(appContext, accountId))

    override fun notificationSettingsFor(accountId: String): NotificationSettingsRepository =
        ChatNotificationSettingsStore(appContext, LocalDataScope.forAccount(appContext, accountId))

    override fun chatViewModelFactory(accountId: String): ViewModelProvider.Factory =
        ChatViewModelFactory { createDependencies(accountId) }

    override fun messagingSessionFactory(accountId: String): ViewModelProvider.Factory =
        MessagingSessionFactory { createDependencies(accountId) }

    override fun applicationServicesFor(accountId: String) = ApplicationServices(
        notifications = notificationSettingsFor(accountId),
        onboarding = OnboardingPreferences(appContext),
        deviceContacts = AndroidDeviceContactsSource(appContext),
        location = locationProvider,
        places = placeSearch,
        venues = venues,
    )

    private fun createDependencies(accountId: String): ChatDependencies {
        val scope = LocalDataScope.forAccount(appContext, accountId)
        // Both contracts use the same manager so events do not create a second Nearby session.
        val nearby = NearbyChatManager(appContext)
        return ChatDependencies(
            nearbyTransport = nearby,
            chatStore = SqliteChatStore(appContext, scope),
            identityStore = LocalIdentityStore(appContext, scope),
            accountId = accountId,
            authRepository = authentication,
            accountProfileRepository = FirebaseAccountProfileRepository(),
            identityFor = ::identityFor,
            cloudChatController = FirebaseCloudChatController(),
            privateProfileStore = privateProfiles,
            notifier = AndroidChatNotifier(appContext, ChatNotificationSettingsStore(appContext, scope)),
            events = EventServices(
                store = SqliteEventStore(appContext, scope),
                remoteRepository = FirebaseEventRemoteRepository(),
                adminKeyStore = LocalEventAdminKeyStore(appContext),
                meshGateway = nearby,
            ),
        )
    }
}
