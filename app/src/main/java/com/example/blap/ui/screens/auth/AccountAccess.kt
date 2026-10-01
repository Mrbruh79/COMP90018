package com.example.blap.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.blap.auth.AuthAccount

@Composable
internal fun AccountAccess(
    authAccount: AuthAccount,
    onCreateEmailAccount: (String, String) -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignInWithGoogle: () -> Unit,
) {
    var accountEmail by rememberSaveable { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var selectedMethod by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(authAccount) { accountPassword = "" }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sign in or link an account", style = MaterialTheme.typography.titleMedium)
            if (authAccount.email.isNotBlank()) {
                Text(authAccount.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Email", "Google").forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedMethod == index,
                        onClick = { selectedMethod = index },
                        label = { Text(label) },
                    )
                }
            }
            when (selectedMethod) {
                0 -> {
                    if (authAccount.hasPassword) {
                        Text("Email and password connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedTextField(
                            value = accountEmail,
                            onValueChange = { accountEmail = it.take(120) },
                            label = { Text("Email") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = accountPassword,
                            onValueChange = { accountPassword = it },
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onCreateEmailAccount(accountEmail, accountPassword) }) {
                                Text(if (authAccount.hasGoogle) "Add email sign-in" else "Create account")
                            }
                            if (!authAccount.hasGoogle) {
                                TextButton(onClick = { onSignInWithEmail(accountEmail, accountPassword) }) {
                                    Text("Sign in")
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (authAccount.hasGoogle) {
                        Text("Google connected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedButton(onClick = onSignInWithGoogle) { Text("Continue with Google") }
                    }
                }
            }
            Text(
                "Nearby chat works offline after sign-in. Phone numbers can be kept on contact cards, but are not used to sign in.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
