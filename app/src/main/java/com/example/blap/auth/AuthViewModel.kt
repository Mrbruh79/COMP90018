package com.example.blap.auth

import androidx.lifecycle.ViewModel
import com.example.blap.chat.MessagingSession
import com.example.blap.chat.PrivateProfileSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
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
    @Volatile private var switchingAccount = false

    fun initialize() {
        if (initialized || switchingAccount) return
        initialized = true
        if (state.value.account.uid.isNotBlank()) loadAccountProfile()
        else if (!state.value.account.isAnonymous) repository.ensureGuestSession {
            if (!scope.isActive || switchingAccount) return@ensureGuestSession
            state.update { it.copy(account = repository.account) }
            session.cloudSync.accountChanged(repository.account.uid)
        }
    }

    fun loadAccountProfile() {
        if (!scope.isActive || switchingAccount) return
        val uid = state.value.account.uid.takeIf(String::isNotBlank) ?: return
        state.update { it.copy(profileLoading = true) }
        scope.launch {
            val local = session.identityStore.getProfile()
            val remote = profileCall { profiles.load() }
            if (!isCurrentAccount(uid)) return@launch
            val profile = remote.getOrNull() ?: if (local.username.isNotBlank() && local.displayName.isNotBlank()) {
                profileCall { profiles.claim(local.username, local.displayName) }.getOrNull()
                    ?: if (remote.isFailure) PublicAccountProfile(local.username, local.displayName) else null
            } else null
            if (!isCurrentAccount(uid)) return@launch
            if (profile != null) {
                val privateCopy = profileCall { session.privateProfileStore?.load(uid) }
                if (!isCurrentAccount(uid)) return@launch
                if (privateCopy.isSuccess) session.profileBackup.restorePrivateProfile(
                    privateCopy.getOrNull(), profile.username, profile.displayName)
                session.accountPublisher.applyAccountProfile(profile.username, profile.displayName)
            } else if (remote.isFailure) session.showError("Could not load your account. Check your connection and retry.")
            state.update { it.copy(profile = profile, profileLoading = false) }
        }
    }

    fun completeAccountProfile(username: String, displayName: String) {
        val uid = state.value.account.uid.takeIf { it.isNotBlank() && isCurrentAccount(it) } ?: return
        scope.launch {
            try {
                val profile = profiles.claim(username, displayName)
                if (!isCurrentAccount(uid)) return@launch
                val identity = session.identityStore
                identity.saveProfile(identity.getProfile().copy(username = profile.username, displayName = profile.displayName))
                state.update { it.copy(profile = profile) }
                session.profileBackup.restorePrivateProfile(null, profile.username, profile.displayName)
                session.accountPublisher.applyAccountProfile(profile.username, profile.displayName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.showError(error.localizedMessage ?: "Could not save your username.")
            }
        }
    }

    fun registerEmail(email: String, password: String, username: String, displayName: String) {
        if (!scope.isActive || switchingAccount) return
        profiles.validate(username, displayName)?.let { session.showError(it); return }
        repository.createEmailAccount(email, password, onSuccess = {
            if (!scope.isActive || switchingAccount) return@createEmailAccount
            val account = repository.account
            state.update { it.copy(account = account, profileLoading = true) }
            scope.launch {
                val setupError = try {
                    val profile = profiles.claim(username, displayName)
                    if (!isCurrentAccount(account.uid)) return@launch
                    val identity = requireNotNull(session.dependencies.identityFor).invoke(account.uid)
                    val saved = identity.getProfile().copy(username = profile.username, displayName = profile.displayName)
                    identity.saveProfile(saved)
                    profileCall { session.privateProfileStore?.save(account.uid, PrivateProfileSnapshot(saved, identity.profileUpdatedAt())) }
                    repository.sendVerificationEmail { }
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error.localizedMessage ?: "Account created, but username setup failed. Try another."
                }
                requestRestart(setupError = setupError)
            }
        }, onError = ::showAuthError)
    }

    fun createEmailAccount(email: String, password: String) {
        if (!scope.isActive || switchingAccount) return
        repository.createEmailAccount(email, password,
            onSuccess = { if (scope.isActive && !switchingAccount) repository.sendVerificationEmail { requestRestart() } },
            onError = ::showAuthError)
    }

    fun signInWithEmail(email: String, password: String) {
        if (scope.isActive && !switchingAccount) repository.signInWithEmail(email, password,
            onSuccess = ::requestRestart, onError = ::showAuthError)
    }

    fun signInWithGoogleToken(token: String) {
        if (scope.isActive && !switchingAccount) repository.signInWithGoogleToken(token,
            onSuccess = ::requestRestart, onError = ::showAuthError)
    }

    fun sendVerificationEmail(onComplete: (Boolean) -> Unit) = repository.sendVerificationEmail(onComplete)
    fun ensureSignedIn(onComplete: (Boolean) -> Unit) = repository.ensureSignedIn(onComplete)

    fun refreshAccount() {
        if (!scope.isActive || switchingAccount) return
        if (repository.account.uid != state.value.account.uid) {
            requestRestart()
            return
        }
        if (state.value.account.uid.isBlank() || state.value.account.emailVerified) return
        val uid = state.value.account.uid
        repository.refreshAccount {
            if (!scope.isActive || switchingAccount) return@refreshAccount
            if (repository.account.uid != uid) { requestRestart(); return@refreshAccount }
            state.update { it.copy(account = repository.account) }
            session.cloudSync.accountChanged(repository.account.uid)
        }
    }

    fun signOut() {
        if (!scope.isActive || switchingAccount) return
        switchingAccount = true
        scope.coroutineContext.cancelChildren()
        session.close()
        repository.signOut()
        session.state.value = com.example.blap.chat.ChatUiState()
        state.value = AuthUiState(accountChange = AccountChange(clearCredentials = true))
    }

    private fun requestRestart(clearCredentials: Boolean = false, setupError: String? = null) {
        if (!scope.isActive || switchingAccount) return
        switchingAccount = true
        session.close()
        state.update { it.copy(accountChange = AccountChange(clearCredentials, setupError)) }
        scope.coroutineContext.cancelChildren()
    }

    private fun isCurrentAccount(uid: String) = scope.isActive && !switchingAccount &&
        state.value.account.uid == uid && repository.account.uid == uid

    private fun showAuthError(message: String) {
        if (scope.isActive && !switchingAccount) session.showError(message)
    }

    private suspend fun <T> profileCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    override fun onCleared() = scope.cancel()
}
