package com.example.blap.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Locale

data class PublicAccountProfile(val username: String, val displayName: String)

object AccountProfileManager {
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val usernamePattern = Regex("^[a-z0-9_]{3,20}$")

    fun normalizeUsername(value: String): String = value.trim().lowercase(Locale.ROOT)

    fun validate(username: String, displayName: String): String? {
        if (!usernamePattern.matches(normalizeUsername(username))) {
            return "Use 3 to 20 letters, numbers or underscores for your username."
        }
        if (displayName.trim().isEmpty() || displayName.trim().length > 24) {
            return "Enter a display name of up to 24 characters."
        }
        return null
    }

    suspend fun load(): PublicAccountProfile? {
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid ?: return null
        val card = firestore.collection("accountCards").document(uid).get().await()
        if (!card.exists()) return null
        val username = card.getString("username").orEmpty()
        val displayName = card.getString("name").orEmpty()
        return if (validate(username, displayName) == null) PublicAccountProfile(username, displayName) else null
    }

    suspend fun claim(username: String, displayName: String): PublicAccountProfile {
        validate(username, displayName)?.let { throw IllegalArgumentException(it) }
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid
            ?: throw IllegalStateException("Sign in first.")
        val normalized = normalizeUsername(username)
        val reservation = firestore.collection("usernames").document(normalized)
        val card = firestore.collection("accountCards").document(uid)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(reservation)
            val existingCard = transaction.get(card)
            if (existing.exists() && existing.getString("uid") != uid) {
                throw IllegalArgumentException("That username is taken. Choose another.")
            }
            val claimedUsername = existingCard.getString("username").orEmpty()
            if (claimedUsername.isNotBlank() && claimedUsername != normalized) {
                throw IllegalArgumentException("This account already has a username.")
            }
            if (!existing.exists()) transaction.set(reservation, mapOf("uid" to uid))
        }.await()
        return PublicAccountProfile(normalized, displayName.trim())
    }
}
