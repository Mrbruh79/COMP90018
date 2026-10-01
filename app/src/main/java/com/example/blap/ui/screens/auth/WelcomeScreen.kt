package com.example.blap.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.blap.R
import com.example.blap.auth.AuthAccount
import com.example.blap.ui.screens.auth.AccountAccess
import com.example.blap.ui.theme.ButtonHeightMedium

@Composable
internal fun WelcomeScreen(
    authAccount: AuthAccount,
    actions: AuthActions,
    name: String,
    nameError: String?,
    deniedPermissions: List<String>,
    onNameChanged: (String) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text("Join CommonGround", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your profile is ready. Nearby messaging works without an Internet connection.",
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountAccess(
                authAccount = authAccount,
                actions = actions,
            )
            Text("Your nearby profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = onNameChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError != null,
                label = { Text("Display Name") },
                supportingText = nameError?.let { message ->
                    { Text(message) }
                },
                trailingIcon = if (nameError != null) {
                    { Icon(painterResource(R.drawable.ic_error), contentDescription = null) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onStart() }),
            )

            if (deniedPermissions.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                        deniedPermissions.forEach { permission ->
                            Text("• $permission", modifier = Modifier.padding(top = 4.dp))
                        }
                        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) {
                            Text("Open settings")
                        }
                    }
                }
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(ButtonHeightMedium),
        ) {
            Text("Continue to chats")
        }
    }
}
