package com.example.blap.ui.theme

import androidx.compose.ui.graphics.Color

// Design tokens from the "Main Design" Figma file (Onboarding page, latest style).
// Keep these as the single source of truth for the dark, high-contrast palette —
// screens should reference these names, not re-declare hex values.

val Background = Color(0xFF0D1117)
val Surface = Color(0xFF161B22)
val SurfaceElevated = Color(0xFF21262D)
val Hairline = Color(0xFF30363D)

val AccentCyan = Color(0xFF00F0FF)
val AccentAmber = Color(0xFFFFB800)

val TextPrimary = Color(0xFFFFFFFF)
val TextMuted = Color(0xFF8B949E)

val Error = Color(0xFFFF453A)

// AccentCyan is too bright to sit behind white text (fails legibility as a fill color,
// even though it works fine as text/icon color on the dark background). Use this instead
// for anywhere text needs to render on top of a cyan-family fill — e.g. sent-message bubbles.
val SentBubble = Color(0xFF137D83)
