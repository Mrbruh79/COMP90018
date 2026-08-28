package com.example.blap.auth

import com.google.firebase.auth.FirebaseAuth

object AuthManager {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    val currentUserId: String?
        get() = auth.currentUser?.uid

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