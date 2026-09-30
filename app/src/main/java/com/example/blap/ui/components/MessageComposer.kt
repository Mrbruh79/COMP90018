package com.example.blap.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
internal fun MessageComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: (String) -> Unit,
    sendLabel: String = "Send",
) {
    val send = {
        if (text.isNotBlank()) {
            onSend(text)
        }
    }

    Row(
        Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                onTextChanged(it.take(1_000))
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            shape = RoundedCornerShape(17.dp),
        )
        Spacer(Modifier.width(9.dp))
        Button(onClick = send, enabled = text.isNotBlank(), modifier = Modifier.height(52.dp)) {
            Text(sendLabel)
        }
    }
}
