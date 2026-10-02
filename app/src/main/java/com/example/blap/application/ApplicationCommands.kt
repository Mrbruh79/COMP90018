package com.example.blap.application

import com.example.blap.chat.ChatScreen
import com.example.blap.chat.DeviceContact
import com.example.blap.event.EventPage
import com.example.blap.location.LocationFix

/** Cross-feature commands injected from the retained feature ViewModels. */
data class ApplicationCommands(
    val startNearby: () -> Unit,
    val importContacts: (List<DeviceContact>) -> Unit,
    val enterEventWithGps: (LocationFix) -> Unit,
    val enterEventWithQr: (String) -> Unit,
    val importContactQr: (String) -> Unit,
    val ensureSignedIn: ((Boolean) -> Unit) -> Unit,
    val refreshAccount: () -> Unit,
    val signInWithGoogleToken: (String) -> Unit,
    val showError: (String) -> Unit,
    val showNotice: (String) -> Unit,
    val venueStatus: (String, Boolean) -> Unit,
    val chatScreen: () -> ChatScreen,
    val eventPage: () -> EventPage,
    val selectedEventId: () -> String?,
    val chatBack: () -> Unit,
    val eventBack: () -> Unit,
    val showChats: () -> Unit,
)
