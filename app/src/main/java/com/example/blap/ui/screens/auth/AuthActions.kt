package com.example.blap.ui.screens.auth
data class AuthActions(
    val onCreateEmailAccount: (String, String) -> Unit,
    val onSignInWithEmail: (String, String) -> Unit,
    val onSignInWithGoogle: () -> Unit,
    val onRegisterEmail: (String, String, String, String) -> Unit,
    val onCompleteAccountProfile: (String, String) -> Unit,
    val onRetryAccountProfile: () -> Unit,
    val onSendVerificationEmail: ((Boolean) -> Unit) -> Unit,
    val onSignOut: () -> Unit,
)
