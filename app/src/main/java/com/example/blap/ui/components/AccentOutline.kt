package com.example.blap.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun accentOutlineBorder(
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
) = BorderStroke(1.dp, if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun accentOutlineColors(
    accent: Color = MaterialTheme.colorScheme.primary,
) = ButtonDefaults.outlinedButtonColors(
    contentColor = accent,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
