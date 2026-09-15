package com.example.blap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.blap.ui.theme.AccentAmber
import com.example.blap.ui.theme.AccentCyan
import com.example.blap.ui.theme.Background
import com.example.blap.ui.theme.CommonGroundTheme
import com.example.blap.ui.theme.SurfaceElevated
import com.example.blap.ui.theme.TextMuted
import com.example.blap.ui.theme.TextPrimary

// Old palette, kept only because screen code still references these names directly
// (not through MaterialTheme). Deprecated in favor of the ui.theme tokens that now
// actually drive the app's appearance — replace call sites as you touch them.
@Deprecated("Use AccentCyan from ui.theme", ReplaceWith("AccentCyan", "com.example.blap.ui.theme.AccentCyan"))
val Forest = Color(0xFF095D55)

@Deprecated("Use TextPrimary from ui.theme", ReplaceWith("TextPrimary", "com.example.blap.ui.theme.TextPrimary"))
val ForestDark = Color(0xFF063E39)

@Deprecated("Use AccentAmber from ui.theme", ReplaceWith("AccentAmber", "com.example.blap.ui.theme.AccentAmber"))
val Signal = Color(0xFF095D55)

@Deprecated("Use Background from ui.theme", ReplaceWith("Background", "com.example.blap.ui.theme.Background"))
val Cream = Color(0xFFF5F7F6)

@Deprecated("Use TextPrimary from ui.theme", ReplaceWith("TextPrimary", "com.example.blap.ui.theme.TextPrimary"))
val Ink = Color(0xFF172220)

@Deprecated("Use SurfaceElevated from ui.theme", ReplaceWith("SurfaceElevated", "com.example.blap.ui.theme.SurfaceElevated"))
val Mist = Color(0xFFDDEAE5)

@Deprecated("Use TextMuted from ui.theme", ReplaceWith("TextMuted", "com.example.blap.ui.theme.TextMuted"))
val MutedInk = Color(0xFF5A6864)

// Delegates to the shared design tokens in ui.theme — this is no longer a separate
// theme, just the name MainActivity already wires up.
@Composable
fun NearbyChatTheme(content: @Composable () -> Unit) {
    CommonGroundTheme(content = content)
}
