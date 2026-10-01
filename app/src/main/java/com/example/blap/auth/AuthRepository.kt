package com.example.blap.auth

interface AuthRepository {
    val account: AuthAccount
    fun ensureGuestSession(onComplete: () -> Unit)
    fun createEmailAccount(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit)
    fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit)
    fun signInWithGoogleToken(token: String, onSuccess: () -> Unit, onError: (String) -> Unit)
    fun sendVerificationEmail(onComplete: (Boolean) -> Unit)
    fun refreshAccount(onComplete: () -> Unit)
    fun ensureSignedIn(onComplete: (Boolean) -> Unit)
    fun signOut()
}

/** Keeps the current provider linking and collision handling. */
class FirebaseAuthRepository : AuthRepository {
    override val account get() = AuthManager.account
    override fun ensureGuestSession(onComplete: () -> Unit) = AuthManager.ensureGuestSession(onComplete)
    override fun createEmailAccount(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        AuthManager.createEmailAccount(email, password, onSuccess, onError)
    override fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        AuthManager.signInWithEmail(email, password, onSuccess, onError)
    override fun signInWithGoogleToken(token: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        AuthManager.signInWithGoogleToken(token, onSuccess, onError)
    override fun sendVerificationEmail(onComplete: (Boolean) -> Unit) = AuthManager.sendVerificationEmail(onComplete)
    override fun refreshAccount(onComplete: () -> Unit) = AuthManager.refreshAccount(onComplete)
    override fun ensureSignedIn(onComplete: (Boolean) -> Unit) = AuthManager.ensureSignedIn(onComplete)
    override fun signOut() = AuthManager.signOut()
}

interface AccountProfileRepository {
    fun validate(username: String, displayName: String): String?
    suspend fun load(): PublicAccountProfile?
    suspend fun claim(username: String, displayName: String): PublicAccountProfile
}

class FirebaseAccountProfileRepository : AccountProfileRepository {
    override fun validate(username: String, displayName: String) = AccountProfileManager.validate(username, displayName)
    override suspend fun load() = AccountProfileManager.load()
    override suspend fun claim(username: String, displayName: String) = AccountProfileManager.claim(username, displayName)
}
