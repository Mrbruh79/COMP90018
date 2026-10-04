package com.example.blap.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.blap.R

@Composable
internal fun ListRow(
    title: String,
    supportingText: String,
    onClick: () -> Unit,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ListRowLayout(
        title = title,
        supportingText = supportingText,
        supportingTextColor = supportingTextColor,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
    }
}

@Composable
internal fun ListToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListRowLayout(title, supportingText, enabled = enabled) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ListRowLayout(
    title: String,
    supportingText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = supportingTextColor.copy(alpha = alpha),
            )
        }
        trailing()
    }
}

private const val DISABLED_ALPHA = 0.38f
