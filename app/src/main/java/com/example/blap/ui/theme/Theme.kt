package com.example.blap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// New dark, high-contrast theme for the onboarding-flow screens (Splash, Sign-in,
// Onboarding, Profile setup), taken from the Figma "Main Design" file. Kept separate
// from the existing NearbyChatTheme (used by the current nearby-chat prototype
// screens) so this work stays additive until the two flows are wired together.
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
    error = Error,
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
