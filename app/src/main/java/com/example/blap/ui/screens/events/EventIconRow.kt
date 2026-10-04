package com.example.blap.ui.screens.events

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** A date or location line: muted leading icon, full-contrast text. Shared by the event list cards and detail page. */
@Composable
internal fun EventIconRow(@DrawableRes iconRes: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
