package com.example.blap.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountGate(
    modifier: Modifier,
    signedIn: Boolean,
    loading: Boolean,
    actions: AuthActions,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var creatingAccount by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("CommonGround", style = MaterialTheme.typography.headlineMedium)
        if (signedIn) {
            Text("Set up your profile", style = MaterialTheme.typography.titleLarge)
            Text("Choose a unique username. Your display name can be shared by other people.")
            if (loading) {
                Text("Loading your account...")
            } else {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(20) },
                    label = { Text("Username") },
                    supportingText = { Text("3 to 20 letters, numbers or underscores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(24) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { actions.onCompleteAccountProfile(username, displayName) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save and continue")
                }
                TextButton(onClick = actions.onRetryAccountProfile) { Text("Retry loading my profile") }
            }
            TextButton(onClick = actions.onSignOut) { Text("Sign out") }
        } else {
            Text("Sign in to see your chats and contacts on this phone.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !creatingAccount, onClick = { creatingAccount = false }, label = { Text("Sign in") })
                FilterChip(selected = creatingAccount, onClick = { creatingAccount = true }, label = { Text("Create account") })
            }
            if (creatingAccount) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(20) },
                    label = { Text("Unique username") },
                    supportingText = { Text("3 to 20 letters, numbers or underscores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(24) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(120) },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (creatingAccount) actions.onRegisterEmail(email, password, username, displayName)
                    else actions.onSignInWithEmail(email, password)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (creatingAccount) "Create account" else "Sign in with email") }
            OutlinedButton(onClick = actions.onSignInWithGoogle, modifier = Modifier.fillMaxWidth()) {
                Text("Continue with Google")
            }
            Text(
                "Phone numbers are optional contact details, not a sign-in method.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
