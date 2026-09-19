package com.example.blap.ui.screens.onboarding

import android.content.Context
import androidx.core.content.edit

class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun hasSeenOnboarding(): Boolean = preferences.getBoolean(KEY_SEEN, false)

    fun markSeen() {
        preferences.edit { putBoolean(KEY_SEEN, true) }
    }

    private companion object {
        const val KEY_SEEN = "has_seen_onboarding"
    }
}
