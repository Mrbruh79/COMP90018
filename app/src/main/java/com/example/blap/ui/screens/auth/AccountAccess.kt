package com.example.blap.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.auth.AuthAccount
import com.example.blap.ui.theme.ButtonHeightMedium

@Composable
internal fun AccountAccess(
    authAccount: AuthAccount,
    actions: AuthActions,
) {
    var accountEmail by rememberSaveable { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    LaunchedEffect(authAccount) { accountPassword = "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sign in to CommonGround", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sign in to sync online contacts and send messages online",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Button(
            onClick = { actions.onSignInWithEmail(accountEmail, accountPassword) },
            modifier = Modifier
                .fillMaxWidth()
                .height(ButtonHeightMedium),
        ) {
            Text("Sign In")
        }
        OutlinedButton(
            onClick = actions.onSignInWithGoogle,
            modifier = Modifier
                .fillMaxWidth()
                .height(ButtonHeightMedium),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                painterResource(R.drawable.ic_google),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified,
            )
            Text("Continue with Google", modifier = Modifier.padding(start = 8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Don't have an account?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { actions.onCreateEmailAccount(accountEmail, accountPassword) }) {
                Text("Create an account")
            }
        }
    }
}
