package com.example.blap.chat

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

interface IdentityStore {
    fun getPeerId(): String
    fun getDisplayName(): String
    fun saveDisplayName(name: String)
    fun getPhoneNumber(): String
    fun savePhoneNumber(phoneNumber: String)
    fun getMeshToken(): String
    fun saveMeshToken(token: String)
    fun getProfile(): ContactProfile = ContactProfile(getDisplayName(), getPhoneNumber())
    fun saveProfile(profile: ContactProfile) {
        saveDisplayName(profile.displayName)
        savePhoneNumber(profile.phoneNumber)
    }
}

class LocalIdentityStore(context: Context) : IdentityStore {
    private val preferences = context.getSharedPreferences("chat_identity", Context.MODE_PRIVATE)

    override fun getPeerId(): String {
        val savedId = preferences.getString("peer_id", null)
        if (!savedId.isNullOrBlank()) return savedId

        val newId = UUID.randomUUID().toString()
        preferences.edit { putString("peer_id", newId) }
        return newId
    }

    override fun getDisplayName(): String = preferences.getString("display_name", "") ?: ""

    override fun saveDisplayName(name: String) {
        preferences.edit { putString("display_name", name) }
    }

    override fun getPhoneNumber(): String = preferences.getString("phone_number", "") ?: ""

    override fun savePhoneNumber(phoneNumber: String) {
        preferences.edit { putString("phone_number", phoneNumber) }
    }

    override fun getMeshToken(): String {
        val saved = preferences.getString("mesh_token", null)
        return saved?.trim()?.ifEmpty { MeshCrypto.DEFAULT_TOKEN } ?: MeshCrypto.DEFAULT_TOKEN
    }

    override fun saveMeshToken(token: String) {
        preferences.edit { putString("mesh_token", token.trim().ifEmpty { MeshCrypto.DEFAULT_TOKEN }) }
    }

    override fun getProfile(): ContactProfile = ContactProfile(
        displayName = getDisplayName(),
        phoneNumber = getPhoneNumber(),
        email = preferences.getString("email", "").orEmpty(),
        bio = preferences.getString("bio", "").orEmpty(),
        websiteUrl = preferences.getString("website", "").orEmpty(),
        instagramUrl = preferences.getString("instagram", "").orEmpty(),
        xUrl = preferences.getString("x", "").orEmpty(),
        linkedinUrl = preferences.getString("linkedin", "").orEmpty(),
        githubUrl = preferences.getString("github", "").orEmpty(),
    )

    override fun saveProfile(profile: ContactProfile) {
        preferences.edit {
            putString("display_name", profile.displayName)
            putString("phone_number", profile.phoneNumber)
            putString("email", profile.email)
            putString("bio", profile.bio)
            putString("website", profile.websiteUrl)
            putString("instagram", profile.instagramUrl)
            putString("x", profile.xUrl)
            putString("linkedin", profile.linkedinUrl)
            putString("github", profile.githubUrl)
        }
    }
}
