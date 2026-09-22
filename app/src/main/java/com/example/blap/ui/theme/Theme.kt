package com.example.blap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// CommonGround App main theme
private val CommonGroundColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = Background,
    secondary = AccentAmber,
    onSecondary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Error,
    errorContainer = Error.copy(alpha = 0.12f),
    onErrorContainer = Error,
    primaryContainer = AccentCyan.copy(alpha = 0.12f),
    onPrimaryContainer = AccentCyan,
    secondaryContainer = AccentAmber.copy(alpha = 0.12f),
    onSecondaryContainer = AccentAmber,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceMuted,
    inverseSurface = SurfaceElevated,
    inverseOnSurface = TextPrimary,
    inversePrimary = AccentCyan,
)

@Composable
fun CommonGroundTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CommonGroundColorScheme,
        typography = CommonGroundTypography,
        shapes = CommonGroundShapes,
        content = content,
    )
}
