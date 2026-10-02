package com.example.blap.application

import androidx.lifecycle.ViewModel
import com.example.blap.chat.ChatNotificationSettings
import com.example.blap.chat.ChatScreen
import com.example.blap.event.EventPage
import com.example.blap.location.GeoCoordinates
import com.example.blap.location.LocationFix
import com.example.blap.location.PlaceSearchResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class AppOperation {
    NEARBY, EVENT_GPS, EVENT_QR, VENUE, CONTACTS, CONTACT_QR,
    GOOGLE, MICROPHONE, NOTIFICATIONS, SETTINGS,
}

enum class PlatformAction {
    NEARBY_PERMISSION, LOCATION_PERMISSION, CONTACTS_PERMISSION, MICROPHONE_PERMISSION,
    NOTIFICATION_PERMISSION, CONTACT_QR, EVENT_QR, GOOGLE_SIGN_IN, APP_SETTINGS,
}

data class PlatformRequest(
    val id: Long,
    val operation: AppOperation,
    val action: PlatformAction,
    val eventId: String? = null,
    val launched: Boolean = false,
    val cancelled: Boolean = false,
)

data class PermissionSnapshot(
    val missingNearby: List<String> = emptyList(),
    val notifications: Boolean = false,
    val microphone: Boolean = false,
)

data class ApplicationUiState(
    val showOnboarding: Boolean,
    val notifications: ChatNotificationSettings,
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val deniedNearby: List<String> = emptyList(),
    val pendingRequest: PlatformRequest? = null,
    val working: Boolean = false,
    val switchingAccount: Boolean = false,
)

/** Owns application decisions. Android only launches requests and reports their results. */
class ApplicationViewModel(
    private val services: ApplicationServices,
    private val commands: ApplicationCommands,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requestIds = AtomicLong()
    private val workVersions = AtomicLong()
    private var work: Job? = null
    private val state = MutableStateFlow(ApplicationUiState(
        showOnboarding = !services.onboarding.hasSeenOnboarding(),
        notifications = services.notifications.load(),
    ))
    val uiState = state.asStateFlow()
    val platformRequests = state.map { it.pendingRequest?.takeUnless { request -> request.launched || request.cancelled } }
        .filterNotNull()

    fun completeOnboarding() {
        if (!scope.isActive) return
        services.onboarding.markSeen()
        state.update { it.copy(showOnboarding = false) }
    }

    fun onResume(snapshot: PermissionSnapshot) {
        if (!scope.isActive) return
        state.update { it.copy(permissions = snapshot,
            deniedNearby = if (it.deniedNearby.isEmpty()) emptyList() else snapshot.missingNearby) }
        commands.refreshAccount()
    }

    fun updateNotificationSettings(value: ChatNotificationSettings) {
        if (!scope.isActive) return
        val old = state.value.notifications
        services.notifications.save(value)
        state.update { it.copy(notifications = value) }
        if (value.enabled && (!old.enabled || (!old.direct && value.direct) ||
            (!old.privateGroups && value.privateGroups) || (!old.openMesh && value.openMesh))) requestNotifications()
    }

    fun requestNearby() = begin(AppOperation.NEARBY, PlatformAction.NEARBY_PERMISSION)
    fun requestEventGps() = begin(AppOperation.EVENT_GPS, PlatformAction.NEARBY_PERMISSION)
    fun requestEventQr() = begin(AppOperation.EVENT_QR, PlatformAction.NEARBY_PERMISSION)
    fun requestContacts() = begin(AppOperation.CONTACTS, PlatformAction.CONTACTS_PERMISSION)
    fun requestContactQr() = begin(AppOperation.CONTACT_QR, PlatformAction.CONTACT_QR)
    fun requestGoogle() = begin(AppOperation.GOOGLE, PlatformAction.GOOGLE_SIGN_IN)
    fun requestMicrophone() = begin(AppOperation.MICROPHONE, PlatformAction.MICROPHONE_PERMISSION)
    fun requestNotifications() = begin(AppOperation.NOTIFICATIONS, PlatformAction.NOTIFICATION_PERMISSION)
    fun requestSettings() = begin(AppOperation.SETTINGS, PlatformAction.APP_SETTINGS)

    @Synchronized
    private fun begin(operation: AppOperation, action: PlatformAction) {
        if (!scope.isActive || state.value.working || state.value.pendingRequest != null) return
        enqueue(operation, action, commands.selectedEventId())
    }

    private fun enqueue(operation: AppOperation, action: PlatformAction, eventId: String? = null) {
        if (!scope.isActive) return
        state.update { it.copy(pendingRequest = PlatformRequest(requestIds.incrementAndGet(), operation, action, eventId)) }
    }

    /** A retained launch marker prevents duplicate permission dialogs after Activity recreation. */
    @Synchronized
    fun claimRequest(id: Long): Boolean {
        val request = state.value.pendingRequest ?: return false
        if (!scope.isActive || request.id != id || request.launched || request.cancelled) return false
        state.update { it.copy(pendingRequest = request.copy(launched = true)) }
        return true
    }

    @Synchronized
    fun retryRequest(id: Long) {
        val request = state.value.pendingRequest ?: return
        if (request.id != id || !scope.isActive) return
        state.update { it.copy(pendingRequest = if (request.cancelled) null else request.copy(launched = false)) }
    }

    @Synchronized
    private fun takeResult(id: Long): PlatformRequest? {
        val request = state.value.pendingRequest ?: return null
        if (request.id != id || !scope.isActive) return null
        state.update { it.copy(pendingRequest = null) }
        if (request.cancelled || (request.operation in EVENT_OPERATIONS && request.eventId != commands.selectedEventId())) return null
        return request
    }

    fun permissionResult(id: Long, missing: List<String>) {
        val request = takeResult(id) ?: return
        when (request.action) {
            PlatformAction.NEARBY_PERMISSION -> {
                state.update { it.copy(deniedNearby = missing) }
                if (missing.isNotEmpty()) return
                commands.startNearby()
                when (request.operation) {
                    AppOperation.EVENT_GPS -> enqueue(request.operation, PlatformAction.LOCATION_PERMISSION, request.eventId)
                    AppOperation.EVENT_QR -> enqueue(request.operation, PlatformAction.EVENT_QR, request.eventId)
                    else -> Unit
                }
            }
            PlatformAction.LOCATION_PERMISSION -> if (missing.isEmpty()) {
                if (request.operation == AppOperation.VENUE) findVenue() else checkEventLocation(request.eventId)
            } else if (request.operation == AppOperation.VENUE) {
                commands.venueStatus("Allow location access to find nearby places.", false)
            } else commands.showError("Allow location access, or use the venue QR.")
            PlatformAction.CONTACTS_PERMISSION -> if (missing.isEmpty()) importContacts()
                else commands.showError("Contacts permission is needed to import device contacts.")
            PlatformAction.MICROPHONE_PERMISSION -> {
                state.update { it.copy(permissions = it.permissions.copy(microphone = missing.isEmpty())) }
                if (missing.isNotEmpty()) commands.showError("Microphone permission is needed to send voice messages.")
            }
            PlatformAction.NOTIFICATION_PERMISSION ->
                state.update { it.copy(permissions = it.permissions.copy(notifications = missing.isEmpty())) }
            else -> Unit
        }
    }

    fun qrResult(id: Long, payload: String?, error: String? = null, cancelled: Boolean = false) {
        val request = takeResult(id) ?: return
        if (cancelled) return
        when {
            error != null -> commands.showError(error)
            payload.isNullOrBlank() -> commands.showError(if (request.action == PlatformAction.EVENT_QR)
                "This QR code has no check-in data." else "This QR code has no readable contact data.")
            request.action == PlatformAction.EVENT_QR -> commands.enterEventWithQr(payload)
            request.action == PlatformAction.CONTACT_QR -> commands.importContactQr(payload)
        }
    }

    fun googleResult(id: Long, token: String? = null, error: String? = null, cancelled: Boolean = false) {
        if (takeResult(id)?.action != PlatformAction.GOOGLE_SIGN_IN) return
        when {
            cancelled -> commands.showNotice("Google sign-in cancelled.")
            error != null -> commands.showError(error)
            token != null -> commands.signInWithGoogleToken(token)
            else -> commands.showError("Google did not return a sign-in token.")
        }
    }

    fun platformFinished(id: Long, error: String? = null) {
        takeResult(id) ?: return
        error?.let(commands.showError)
    }

    @Synchronized
    fun requestVenue() {
        if (!scope.isActive || state.value.working || state.value.pendingRequest != null) return
        val version = workVersions.incrementAndGet()
        state.update { it.copy(working = true) }
        commands.venueStatus("Connecting to nearby places...", true)
        commands.ensureSignedIn { success ->
            if (!scope.isActive || version != workVersions.get()) return@ensureSignedIn
            state.update { it.copy(working = false) }
            if (success) enqueue(AppOperation.VENUE, PlatformAction.LOCATION_PERMISSION)
            else commands.venueStatus("Could not connect. Check your internet and try again.", false)
        }
    }

    private fun importContacts() = runWork {
        try {
            val contacts = services.deviceContacts.read()
            currentCoroutineContext().ensureActive()
            commands.importContacts(contacts)
        }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { commands.showError("Could not read device contacts. Check permission and try again.") }
    }

    private fun findVenue() = runWork {
        commands.venueStatus("Looking for a nearby place...", true)
        val message = try {
            services.venues.findNearbyVenue()?.let { "Nearby: ${it.name}" } ?: "No places found nearby."
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { "Could not find nearby places. Check your internet and location settings." }
        currentCoroutineContext().ensureActive()
        commands.venueStatus(message, false)
    }

    private fun checkEventLocation(eventId: String?) = runWork {
        try {
            val fix = services.location.getFreshLocation()
            currentCoroutineContext().ensureActive()
            if (eventId != commands.selectedEventId()) return@runWork
            if (fix == null) commands.showError("A current location was not available. Use the venue QR.")
            else commands.enterEventWithGps(fix)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { commands.showError("Location could not be checked. Use the venue QR.") }
    }

    private fun runWork(block: suspend () -> Unit) {
        val version = workVersions.incrementAndGet()
        state.update { it.copy(working = true) }
        work = scope.launch {
            try { block() }
            finally { if (version == workVersions.get()) state.update { it.copy(working = false) } }
        }
    }

    suspend fun currentLocation(): LocationFix? {
        if (!scope.isActive) throw CancellationException("Account changed")
        return services.location.getFreshLocation().also {
            if (!scope.isActive) throw CancellationException("Account changed")
        }
    }
    suspend fun searchPlaces(query: String): List<PlaceSearchResult> {
        if (!scope.isActive) throw CancellationException("Account changed")
        return services.places.search(query).also {
            if (!scope.isActive) throw CancellationException("Account changed")
        }
    }
    suspend fun addressForCoordinates(coordinates: GeoCoordinates): String? {
        if (!scope.isActive) throw CancellationException("Account changed")
        return services.places.reverse(coordinates).also {
            if (!scope.isActive) throw CancellationException("Account changed")
        }
    }

    fun handleBack() {
        if (!scope.isActive) return
        cancelPendingWork()
        when {
            commands.chatScreen() != ChatScreen.EVENTS -> commands.chatBack()
            commands.eventPage() == EventPage.LIST -> commands.showChats()
            else -> commands.eventBack()
        }
    }

    private fun cancelPendingWork() {
        workVersions.incrementAndGet()
        work?.cancel()
        state.update { it.copy(working = false,
            pendingRequest = it.pendingRequest?.takeIf { request -> request.launched }?.copy(cancelled = true)) }
    }

    fun freezeForAccountChange() {
        workVersions.incrementAndGet()
        scope.cancel()
        state.update { it.copy(pendingRequest = null, working = false, switchingAccount = true) }
    }

    override fun onCleared() = freezeForAccountChange()

    private companion object {
        val EVENT_OPERATIONS = setOf(AppOperation.EVENT_GPS, AppOperation.EVENT_QR)
    }
}
