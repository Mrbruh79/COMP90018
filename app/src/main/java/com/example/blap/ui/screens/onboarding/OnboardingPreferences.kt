package com.example.blap.ui.screens.onboarding

import android.content.Context
import androidx.core.content.edit
import com.example.blap.application.OnboardingStore

class OnboardingPreferences(context: Context) : OnboardingStore {
    private val preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    override fun hasSeenOnboarding(): Boolean = preferences.getBoolean(KEY_SEEN, false)

    override fun markSeen() {
        preferences.edit { putBoolean(KEY_SEEN, true) }
    }

    private companion object {
        const val KEY_SEEN = "has_seen_onboarding"
    }
}
