package com.example.blap.application

import com.example.blap.chat.DeviceContact
import com.example.blap.chat.NotificationSettingsRepository
import com.example.blap.location.LocationProvider
import com.example.blap.location.PlaceSearchRepository
import com.example.blap.venue.VenueRepository

interface OnboardingStore {
    fun hasSeenOnboarding(): Boolean
    fun markSeen()
}

fun interface DeviceContactsSource {
    suspend fun read(): List<DeviceContact>
}

/** Platform implementations retain application context, not an Activity. */
data class ApplicationServices(
    val notifications: NotificationSettingsRepository,
    val onboarding: OnboardingStore,
    val deviceContacts: DeviceContactsSource,
    val location: LocationProvider,
    val places: PlaceSearchRepository,
    val venues: VenueRepository,
)
