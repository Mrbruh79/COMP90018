package com.example.blap.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider

data class AuthAccount(
    val uid: String = "",
    val email: String = "",
    val emailVerified: Boolean = false,
    val hasPassword: Boolean = false,
    val hasGoogle: Boolean = false,
)

object AuthManager {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val currentUserId: String?
        get() = auth.currentUser?.uid

    val onlineUserId: String
        get() = auth.currentUser?.takeUnless { it.isAnonymous }?.uid.orEmpty()

    val account: AuthAccount
        get() {
            val user = auth.currentUser
            val providers = user?.providerData?.map { it.providerId }.orEmpty()
            return AuthAccount(
                uid = user?.takeUnless { it.isAnonymous }?.uid.orEmpty(),
                email = user?.email.orEmpty(),
                emailVerified = user?.isEmailVerified == true,
                hasPassword = EmailAuthProvider.PROVIDER_ID in providers,
                hasGoogle = GoogleAuthProvider.PROVIDER_ID in providers,
            )
        }

    fun createEmailAccount(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val trimmedEmail = email.trim()
        if (!validEmailPassword(trimmedEmail, password, onError)) return
        val user = auth.currentUser
        if (account.hasPassword) {
            onError("An email and password are already linked to this account.")
            return
        }
        if (user == null) {
            auth.createUserWithEmailAndPassword(trimmedEmail, password).addOnCompleteListener { result ->
                if (result.isSuccessful) onSuccess()
                else onError(result.exception?.localizedMessage ?: "Could not create the account.")
            }
        } else {
            val credential = EmailAuthProvider.getCredential(trimmedEmail, password)
            user.linkWithCredential(credential).addOnCompleteListener { result ->
                if (result.isSuccessful) onSuccess()
                else onError(result.exception?.localizedMessage ?: "Could not add email sign-in to this account.")
            }
        }
    }

    fun signInWithEmail(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val trimmedEmail = email.trim()
        if (!validEmailPassword(trimmedEmail, password, onError)) return
        if (auth.currentUser?.isAnonymous == false) {
            onError("An account is already signed in. Add this email to that account instead of switching.")
            return
        }
        auth.signInWithEmailAndPassword(trimmedEmail, password).addOnCompleteListener { result ->
            if (result.isSuccessful) onSuccess()
            else onError(result.exception?.localizedMessage ?: "Could not sign in.")
        }
    }

    fun signInWithGoogleToken(
        idToken: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = auth.currentUser
        if (account.hasGoogle) {
            onError("A Google account is already linked to this account.")
            return
        }
        if (user != null) {
            user.linkWithCredential(credential).addOnCompleteListener { result ->
                if (result.isSuccessful) onSuccess()
                else if (user.isAnonymous && result.exception is FirebaseAuthUserCollisionException) {
                    auth.signInWithCredential(credential).addOnCompleteListener { signIn ->
                        if (signIn.isSuccessful) onSuccess()
                        else onError(signIn.exception?.localizedMessage ?: "Could not sign in with Google.")
                    }
                } else if (result.exception is FirebaseAuthUserCollisionException) {
                    onError("This Google account belongs to another user. Account merging is not supported yet.")
                } else onError(result.exception?.localizedMessage ?: "Could not link this Google account.")
            }
        } else {
            auth.signInWithCredential(credential).addOnCompleteListener { result ->
                if (result.isSuccessful) onSuccess()
                else onError(result.exception?.localizedMessage ?: "Could not sign in with Google.")
            }
        }
    }

    private fun validEmailPassword(email: String, password: String, onError: (String) -> Unit): Boolean {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            onError("Enter a valid email address.")
            return false
        }
        if (password.length < 6) {
            onError("Use a password with at least 6 characters.")
            return false
        }
        return true
    }

    fun sendVerificationEmail(onComplete: (Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null || user.email.isNullOrBlank() || user.isEmailVerified) {
            onComplete(false)
            return
        }
        user.sendEmailVerification().addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun refreshAccount(onComplete: () -> Unit) {
        val user = auth.currentUser
        if (user == null) onComplete()
        else user.reload().addOnCompleteListener { reload ->
            if (reload.isSuccessful) user.getIdToken(true).addOnCompleteListener { onComplete() }
            else onComplete()
        }
    }

    fun ensureSignedIn(onComplete: (success: Boolean) -> Unit) {
        val existingUser = auth.currentUser
        if (existingUser != null) {
            onComplete(true)
            return
        }
        auth.signInAnonymously()
            .addOnCompleteListener { task ->
                onComplete(task.isSuccessful)
            }
    }
}
