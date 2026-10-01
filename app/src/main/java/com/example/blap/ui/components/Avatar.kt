package com.example.blap.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun Avatar(
    name: String,
    connected: Boolean,
    @DrawableRes iconRes: Int? = null,
) {
    val content = if (connected) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (connected) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, tint = content)
        } else {
            Text(initialsOf(name), color = content, fontWeight = FontWeight.Bold)
        }
    }
}

private fun initialsOf(name: String) = name
    .split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
