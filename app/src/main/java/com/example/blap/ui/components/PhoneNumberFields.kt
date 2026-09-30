package com.example.blap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.blap.chat.PhoneNumberParts

@Composable
internal fun PhoneNumberFields(number: String, label: String = "Phone number (optional)", onChanged: (String) -> Unit) {
    val initial = remember { PhoneNumberParts.from(number) }
    var countryCode by rememberSaveable { mutableStateOf(initial.countryCode) }
    var nationalNumber by rememberSaveable { mutableStateOf(initial.nationalNumber) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = countryCode,
            onValueChange = { value ->
                countryCode = value.filter(Char::isDigit).take(3)
                onChanged(PhoneNumberParts(countryCode, nationalNumber).combined())
            },
            modifier = Modifier.width(104.dp),
            label = { Text("Code") },
            prefix = { Text("+") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = nationalNumber,
            onValueChange = { value ->
                nationalNumber = value.filter(Char::isDigit).take(15)
                onChanged(PhoneNumberParts(countryCode, nationalNumber).combined())
            },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}
