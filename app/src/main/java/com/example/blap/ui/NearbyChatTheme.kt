package com.example.blap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF095D55)
val ForestDark = Color(0xFF063E39)
val Signal = Color(0xFFFF6B4A)
val Cream = Color(0xFFF6F1E7)
val Ink = Color(0xFF172220)
val Mist = Color(0xFFDDEAE5)
val MutedInk = Color(0xFF5A6864)

private val NearbyColorScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Mist,
    onPrimaryContainer = ForestDark,
    secondary = Signal,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Color(0xFFFFFCF6),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E5DC),
    onSurfaceVariant = MutedInk,
    error = Color(0xFFB3261E),
)

private val NearbyTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun NearbyChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NearbyColorScheme,
        typography = NearbyTypography,
        content = content,
    )
}
