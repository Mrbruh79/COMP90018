package com.example.blap.auth

import androidx.lifecycle.ViewModel
import com.example.blap.chat.MessagingSession
import com.example.blap.chat.PrivateProfileSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AuthUiState(
    val account: AuthAccount = AuthAccount(),
    val profile: PublicAccountProfile? = null,
    val profileLoading: Boolean = false,
    val accountChange: AccountChange? = null,
)

data class AccountChange(val clearCredentials: Boolean = false, val setupError: String? = null)

/** Account setup and provider operations. Credential chooser UI remains in the Activity. */
class AuthViewModel(private val session: MessagingSession) : ViewModel() {
    private val repository = requireNotNull(session.dependencies.authRepository)
    private val profiles = requireNotNull(session.dependencies.accountProfileRepository)
    private val scope = CoroutineScope(SupervisorJob() + session.dependencies.ioDispatcher)
    private val state = MutableStateFlow(AuthUiState(repository.account))
    val uiState = state.asStateFlow()
    // Retain an unhandled account change across Activity recreation until the owner is cleared.
    val accountChanges = state.map { it.accountChange }.filterNotNull().distinctUntilChanged()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        if (state.value.account.uid.isNotBlank()) loadAccountProfile()
        else if (!state.value.account.isAnonymous) repository.ensureGuestSession {
            if (!scope.isActive) return@ensureGuestSession
            state.update { it.copy(account = repository.account) }
            session.cloudSync.accountChanged(repository.account.uid)
        }
    }

    fun loadAccountProfile() {
        val uid = state.value.account.uid.takeIf(String::isNotBlank) ?: return
        state.update { it.copy(profileLoading = true) }
        scope.launch {
            val local = session.identityStore.getProfile()
            val remote = runCatching { profiles.load() }
            val profile = remote.getOrNull() ?: if (local.username.isNotBlank() && local.displayName.isNotBlank()) {
                runCatching { profiles.claim(local.username, local.displayName) }.getOrNull()
                    ?: if (remote.isFailure) PublicAccountProfile(local.username, local.displayName) else null
            } else null
            if (state.value.account.uid != uid || repository.account.uid != uid) return@launch
            if (profile != null) {
                val privateCopy = runCatching { session.privateProfileStore?.load(uid) }
                if (privateCopy.isSuccess) session.profileBackup.restorePrivateProfile(
                    privateCopy.getOrNull(), profile.username, profile.displayName)
                session.accountPublisher.applyAccountProfile(profile.username, profile.displayName)
            } else if (remote.isFailure) session.showError("Could not load your account. Check your connection and retry.")
            state.update { it.copy(profile = profile, profileLoading = false) }
        }
    }

    fun completeAccountProfile(username: String, displayName: String) {
        scope.launch {
            try {
                val profile = profiles.claim(username, displayName)
                val identity = session.identityStore
                identity.saveProfile(identity.getProfile().copy(username = profile.username, displayName = profile.displayName))
                state.update { it.copy(profile = profile) }
                session.profileBackup.restorePrivateProfile(null, profile.username, profile.displayName)
                session.accountPublisher.applyAccountProfile(profile.username, profile.displayName)
            } catch (error: Exception) {
                session.showError(error.localizedMessage ?: "Could not save your username.")
            }
        }
    }

    fun registerEmail(email: String, password: String, username: String, displayName: String) {
        profiles.validate(username, displayName)?.let { session.showError(it); return }
        repository.createEmailAccount(email, password, onSuccess = {
            if (!scope.isActive) return@createEmailAccount
            val account = repository.account
            state.update { it.copy(account = account, profileLoading = true) }
            scope.launch {
                val setupError = try {
                    val profile = profiles.claim(username, displayName)
                    val identity = requireNotNull(session.dependencies.identityFor).invoke(account.uid)
                    val saved = identity.getProfile().copy(username = profile.username, displayName = profile.displayName)
                    identity.saveProfile(saved)
                    runCatching { session.privateProfileStore?.save(account.uid, PrivateProfileSnapshot(saved, identity.profileUpdatedAt())) }
                    repository.sendVerificationEmail { }
                    null
                } catch (error: Exception) {
                    error.localizedMessage ?: "Account created, but username setup failed. Try another."
                }
                requestRestart(setupError = setupError)
            }
        }, onError = session::showError)
    }

    fun createEmailAccount(email: String, password: String) = repository.createEmailAccount(email, password,
        onSuccess = { repository.sendVerificationEmail { requestRestart() } }, onError = session::showError)

    fun signInWithEmail(email: String, password: String) = repository.signInWithEmail(email, password,
        onSuccess = ::requestRestart, onError = session::showError)

    fun signInWithGoogleToken(token: String) = repository.signInWithGoogleToken(token,
        onSuccess = ::requestRestart, onError = session::showError)

    fun sendVerificationEmail(onComplete: (Boolean) -> Unit) = repository.sendVerificationEmail(onComplete)
    fun ensureSignedIn(onComplete: (Boolean) -> Unit) = repository.ensureSignedIn(onComplete)

    fun refreshAccount() {
        if (state.value.account.uid.isBlank() || state.value.account.emailVerified) return
        repository.refreshAccount {
            if (!scope.isActive) return@refreshAccount
            state.update { it.copy(account = repository.account) }
            session.cloudSync.accountChanged(repository.account.uid)
        }
    }

    fun signOut() {
        session.close()
        repository.signOut()
        state.value = AuthUiState()
        session.state.value = com.example.blap.chat.ChatUiState()
        requestRestart(clearCredentials = true)
    }

    private fun requestRestart(clearCredentials: Boolean = false, setupError: String? = null) {
        if (scope.isActive) state.update { it.copy(accountChange = AccountChange(clearCredentials, setupError)) }
    }

    override fun onCleared() = scope.cancel()
}
