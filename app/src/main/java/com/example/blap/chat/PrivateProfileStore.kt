package com.example.blap.chat

import com.example.blap.auth.AuthManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class PrivateProfileSnapshot(val profile: ContactProfile, val updatedAt: Long)

interface PrivateProfileStore {
    suspend fun load(uid: String): PrivateProfileSnapshot?
    suspend fun save(uid: String, snapshot: PrivateProfileSnapshot)
}

/** Owner-only account backup. Public cards and discovery indexes remain separate. */
class FirebasePrivateProfileStore(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : PrivateProfileStore {
    override suspend fun load(uid: String): PrivateProfileSnapshot? {
        require(AuthManager.onlineUserId == uid)
        val document = firestore.collection("privateProfiles").document(uid).get().await()
        if (!document.exists()) return null
        val profile = ContactProfile(
            displayName = document.getString("displayName").orEmpty(),
            phoneNumber = document.getString("phoneNumber").orEmpty(),
            email = document.getString("email").orEmpty(),
            googleAccountEmail = document.getString("googleAccountEmail").orEmpty(),
            discoverableByPhone = document.getBoolean("discoverableByPhone") ?: false,
            bio = document.getString("bio").orEmpty(),
            websiteUrl = document.getString("websiteUrl").orEmpty(),
            instagramUrl = document.getString("instagramUrl").orEmpty(),
            xUrl = document.getString("xUrl").orEmpty(),
            linkedinUrl = document.getString("linkedinUrl").orEmpty(),
            githubUrl = document.getString("githubUrl").orEmpty(),
            username = document.getString("username").orEmpty(),
            lookupPhoneNumber = document.getString("lookupPhoneNumber").orEmpty(),
        )
        return PrivateProfileSnapshot(profile, document.getLong("updatedAt") ?: 0L)
    }

    override suspend fun save(uid: String, snapshot: PrivateProfileSnapshot) {
        require(AuthManager.onlineUserId == uid)
        val profile = snapshot.profile
        firestore.collection("privateProfiles").document(uid).set(mapOf(
            "uid" to uid,
            "updatedAt" to snapshot.updatedAt,
            "displayName" to profile.displayName,
            "username" to profile.username,
            "phoneNumber" to profile.phoneNumber,
            "email" to profile.email,
            "googleAccountEmail" to profile.googleAccountEmail,
            "discoverableByPhone" to profile.discoverableByPhone,
            "lookupPhoneNumber" to profile.lookupPhoneNumber,
            "bio" to profile.bio,
            "websiteUrl" to profile.websiteUrl,
            "instagramUrl" to profile.instagramUrl,
            "xUrl" to profile.xUrl,
            "linkedinUrl" to profile.linkedinUrl,
            "githubUrl" to profile.githubUrl,
        )).await()
    }
}
